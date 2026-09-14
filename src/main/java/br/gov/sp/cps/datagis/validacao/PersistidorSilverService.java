package br.gov.sp.cps.datagis.validacao;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Responsável pela persistência física dos registros aprovados
 * nas tabelas de fato da camada Silver (silver.*), resolvendo chaves estrangeiras,
 * convertendo geometrias para MDSYS.SDO_GEOMETRY (SRID 4674) e preservando
 * atributos originais e linhagem cross-schema.
 */
@Service
public class PersistidorSilverService {

    private static final Logger log = LoggerFactory.getLogger(PersistidorSilverService.class);

    private final JdbcTemplate jdbcTemplate;
    private final String silverSchema;
    private final ObjectMapper objectMapper;

    /**
     * Cache thread-safe de tabelas de referência:
     * Chave externa: nome da tabela (ex: "situacao_imovel")
     * Mapa interno: termo de busca em maiúsculas (código ou descrição) -> ID primário numérico.
     */
    private final ConcurrentHashMap<String, Map<String, Long>> refCache = new ConcurrentHashMap<>();

    @Autowired
    public PersistidorSilverService(
            JdbcTemplate jdbcTemplate,
            @Value("${spring.flyway.placeholders.silverSchema:SILVER}") String silverSchema,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.silverSchema = (silverSchema != null && !silverSchema.isBlank()) ? silverSchema.trim() : "SILVER";
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public PersistidorSilverService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, "SILVER", new ObjectMapper());
    }

    /**
     * Persiste uma lista de registros aprovados na entidade Silver correspondente.
     *
     * @param entidade nome canônico da entidade (ex: "imovel_rural", "municipio", "auto_infracao")
     * @param aprovados lista de registros aprovados pelo motor de validação
     * @return quantidade de registros persistidos com sucesso
     */
    @Transactional
    public int persistirAprovados(String entidade, List<RegistroAprovadoSilver> aprovados) {
        if (aprovados == null || aprovados.isEmpty()) {
            return 0;
        }

        String ent = normalizarNomeEntidade(entidade);
        int inseridos = 0;

        for (RegistroAprovadoSilver apr : aprovados) {
            try {
                switch (ent) {
                    case "imovel_rural" -> persistirImovelRural(apr);
                    case "municipio" -> persistirMunicipio(apr);
                    case "area_protegida" -> persistirAreaProtegida(apr);
                    case "auto_infracao" -> persistirAutoInfracao(apr);
                    case "embargo_ambiental" -> persistirEmbargoAmbiental(apr);
                    case "desmatamento" -> persistirDesmatamento(apr);
                    case "foco_calor" -> persistirFocoCalor(apr);
                    default -> persistirGenerico(ent, apr);
                }
                inseridos++;
            } catch (Exception e) {
                log.error("Erro ao persistir registro bruto {} na Silver {}: {}",
                        apr.registroBruto() != null ? apr.registroBruto().getId() : "null",
                        ent, e.getMessage(), e);
                throw new RuntimeException("Falha na persistência atômica da Silver para entidade " + ent + ": " + e.getMessage(), e);
            }
        }

        log.info("Persistidos com sucesso {} registros na camada Silver ({}.{})", inseridos, silverSchema, ent);
        return inseridos;
    }

    // -------------------------------------------------------------------------
    // Persistência Específica por Entidade Fato
    // -------------------------------------------------------------------------

    private void persistirImovelRural(RegistroAprovadoSilver apr) {
        Map<String, Object> attrs = apr.atributosTransformados();
        RegistroBruto reg = apr.registroBruto();

        String codImovel = extrairString(attrs, "cod_imovel");
        if (codImovel == null && reg != null) {
            codImovel = "REG-" + reg.getId();
        }

        Long situacaoId = resolverIdReferencia("situacao_imovel", attrs.get("situacao_imovel"));
        if (situacaoId == null) {
            situacaoId = resolverIdReferencia("situacao_imovel", attrs.get("status_imovel"));
        }
        Long condicaoId = resolverIdReferencia("condicao_imovel", attrs.get("condicao_imovel"));
        if (condicaoId == null) {
            condicaoId = resolverIdReferencia("condicao_imovel", attrs.get("condicao"));
        }
        Long tipoId = resolverIdReferencia("tipo_imovel", attrs.get("tipo_imovel"));
        if (tipoId == null) {
            tipoId = resolverIdReferencia("tipo_imovel", attrs.get("tipo"));
        }
        Long municipioId = resolverIdMunicipio(attrs.get("municipio"), attrs.get("cod_municipio_ibge"));

        BigDecimal areaHa = extrairBigDecimal(attrs, "area_ha", "area");
        BigDecimal modulosFiscais = extrairBigDecimal(attrs, "modulos_fiscais", "m_fiscal");
        Date dataRegistro = extrairData(attrs, "data_registro");
        String geomWkt = apr.geometriaWkt();
        String atributosOrigem = extrairPayloadOuJson(reg, attrs);

        String sql = """
            MERGE INTO %s.imovel_rural t
            USING (
                SELECT
                    s.cod_imovel,
                    s.situacao_imovel_id,
                    s.condicao_imovel_id,
                    s.tipo_imovel_id,
                    s.municipio_id,
                    s.data_registro,
                    s.area_ha,
                    s.modulos_fiscais,
                    CASE WHEN s.wkt IS NOT NULL THEN SDO_GEOMETRY(s.wkt, 4674) ELSE NULL END AS geometria,
                    s.atributos_origem,
                    s.fonte_dado_id,
                    s.lote_ingestao_id,
                    s.registro_bruto_id,
                    s.hash_origem
                FROM (
                    SELECT
                        ? AS cod_imovel,
                        ? AS situacao_imovel_id,
                        ? AS condicao_imovel_id,
                        ? AS tipo_imovel_id,
                        ? AS municipio_id,
                        ? AS data_registro,
                        ? AS area_ha,
                        ? AS modulos_fiscais,
                        TO_CLOB(?) AS wkt,
                        TO_CLOB(?) AS atributos_origem,
                        ? AS fonte_dado_id,
                        ? AS lote_ingestao_id,
                        ? AS registro_bruto_id,
                        ? AS hash_origem
                    FROM dual
                ) s
            ) s ON (t.cod_imovel = s.cod_imovel)
            WHEN MATCHED THEN
                UPDATE SET
                    t.situacao_imovel_id = s.situacao_imovel_id,
                    t.condicao_imovel_id = s.condicao_imovel_id,
                    t.tipo_imovel_id     = s.tipo_imovel_id,
                    t.municipio_id       = s.municipio_id,
                    t.data_registro      = s.data_registro,
                    t.area_ha            = s.area_ha,
                    t.modulos_fiscais    = s.modulos_fiscais,
                    t.geometria          = COALESCE(s.geometria, t.geometria),
                    t.atributos_origem   = s.atributos_origem,
                    t.fonte_dado_id      = s.fonte_dado_id,
                    t.lote_ingestao_id   = s.lote_ingestao_id,
                    t.registro_bruto_id  = s.registro_bruto_id,
                    t.data_processamento = SYSTIMESTAMP,
                    t.hash_origem        = s.hash_origem
            WHEN NOT MATCHED THEN
                INSERT (
                    cod_imovel, situacao_imovel_id, condicao_imovel_id, tipo_imovel_id,
                    municipio_id, data_registro, area_ha, modulos_fiscais,
                    geometria, atributos_origem, fonte_dado_id, lote_ingestao_id,
                    registro_bruto_id, data_processamento, hash_origem
                ) VALUES (
                    s.cod_imovel, s.situacao_imovel_id, s.condicao_imovel_id, s.tipo_imovel_id,
                    s.municipio_id, s.data_registro, s.area_ha, s.modulos_fiscais,
                    s.geometria, s.atributos_origem, s.fonte_dado_id, s.lote_ingestao_id,
                    s.registro_bruto_id, SYSTIMESTAMP, s.hash_origem
                )
            """.formatted(silverSchema);

        executarMerge(sql,
                codImovel,
                situacaoId,
                condicaoId,
                tipoId,
                municipioId,
                dataRegistro,
                areaHa,
                modulosFiscais,
                geomWkt,
                atributosOrigem,
                reg != null && reg.getFonteDadoId() != null ? reg.getFonteDadoId() : 1L,
                reg != null && reg.getLoteIngestaoId() != null ? reg.getLoteIngestaoId() : 1L,
                reg != null && reg.getId() != null ? reg.getId() : 1L,
                reg != null ? reg.getHashRegistro() : null
        );
    }

    private void persistirMunicipio(RegistroAprovadoSilver apr) {
        Map<String, Object> attrs = apr.atributosTransformados();
        RegistroBruto reg = apr.registroBruto();

        Long codIbge = extrairLong(attrs, "cod_ibge", "cd_mun");
        String nome = extrairString(attrs, "nome", "nm_mun");
        Long ufId = resolverIdReferencia("unidade_federativa", attrs.get("uf"));
        if (ufId == null) {
            ufId = resolverIdReferencia("unidade_federativa", "PR");
        }
        Long biomaId = resolverIdReferencia("bioma", attrs.get("bioma"));
        BigDecimal areaKm2 = extrairBigDecimal(attrs, "area_km2", "area");
        String geomWkt = apr.geometriaWkt();
        String atributosOrigem = extrairPayloadOuJson(reg, attrs);

        String sql = """
            MERGE INTO %s.municipio t
            USING (
                SELECT
                    s.cod_ibge,
                    s.nome,
                    s.unidade_federativa_id,
                    s.bioma_predominante_id,
                    s.area_km2,
                    CASE WHEN s.wkt IS NOT NULL THEN SDO_GEOMETRY(s.wkt, 4674) ELSE NULL END AS geometria,
                    s.atributos_origem,
                    s.fonte_dado_id,
                    s.lote_ingestao_id,
                    s.registro_bruto_id,
                    s.hash_origem
                FROM (
                    SELECT
                        ? AS cod_ibge,
                        ? AS nome,
                        ? AS unidade_federativa_id,
                        ? AS bioma_predominante_id,
                        ? AS area_km2,
                        TO_CLOB(?) AS wkt,
                        TO_CLOB(?) AS atributos_origem,
                        ? AS fonte_dado_id,
                        ? AS lote_ingestao_id,
                        ? AS registro_bruto_id,
                        ? AS hash_origem
                    FROM dual
                ) s
            ) s ON (t.cod_ibge = s.cod_ibge)
            WHEN MATCHED THEN
                UPDATE SET
                    t.nome                  = s.nome,
                    t.unidade_federativa_id = s.unidade_federativa_id,
                    t.bioma_predominante_id = s.bioma_predominante_id,
                    t.area_km2              = s.area_km2,
                    t.geometria             = COALESCE(s.geometria, t.geometria),
                    t.atributos_origem      = s.atributos_origem,
                    t.fonte_dado_id         = s.fonte_dado_id,
                    t.lote_ingestao_id      = s.lote_ingestao_id,
                    t.registro_bruto_id     = s.registro_bruto_id,
                    t.data_processamento    = SYSTIMESTAMP,
                    t.hash_origem           = s.hash_origem
            WHEN NOT MATCHED THEN
                INSERT (
                    cod_ibge, nome, unidade_federativa_id, bioma_predominante_id,
                    area_km2, geometria, atributos_origem, fonte_dado_id,
                    lote_ingestao_id, registro_bruto_id, data_processamento, hash_origem
                ) VALUES (
                    s.cod_ibge, s.nome, s.unidade_federativa_id, s.bioma_predominante_id,
                    s.area_km2, s.geometria, s.atributos_origem, s.fonte_dado_id,
                    s.lote_ingestao_id, s.registro_bruto_id, SYSTIMESTAMP, s.hash_origem
                )
            """.formatted(silverSchema);

        executarMerge(sql,
                codIbge,
                nome,
                ufId,
                biomaId,
                areaKm2,
                geomWkt,
                atributosOrigem,
                reg != null && reg.getFonteDadoId() != null ? reg.getFonteDadoId() : 7L,
                reg != null && reg.getLoteIngestaoId() != null ? reg.getLoteIngestaoId() : 1L,
                reg != null && reg.getId() != null ? reg.getId() : 1L,
                reg != null ? reg.getHashRegistro() : null
        );
    }

    private void persistirAreaProtegida(RegistroAprovadoSilver apr) {
        Map<String, Object> attrs = apr.atributosTransformados();
        RegistroBruto reg = apr.registroBruto();

        String codigoOrigem = extrairString(attrs, "codigo_origem", "cnuc", "terrai_cod", "id");
        String nome = extrairString(attrs, "nome", "nm_uc", "terrai_nom");
        Long orgaoId = resolverIdOrgao(attrs.get("orgao"), "ICMBIO");
        Long tipoAreaId = resolverIdReferencia("tipo_area_protegida", attrs.get("tipo_area_protegida"));
        if (tipoAreaId == null) {
            tipoAreaId = 1L; // Fallback
        }
        Long biomaId = resolverIdReferencia("bioma", attrs.get("bioma"));
        String atoLegal = extrairString(attrs, "ato_legal");
        BigDecimal areaHa = extrairBigDecimal(attrs, "area_ha", "area");
        String geomWkt = apr.geometriaWkt();
        String atributosOrigem = extrairPayloadOuJson(reg, attrs);

        String sql = """
            MERGE INTO %s.area_protegida t
            USING (
                SELECT
                    s.codigo_origem,
                    s.orgao_id,
                    s.nome,
                    s.tipo_area_protegida_id,
                    s.bioma_id,
                    s.ato_legal,
                    s.area_ha,
                    CASE WHEN s.wkt IS NOT NULL THEN SDO_GEOMETRY(s.wkt, 4674) ELSE NULL END AS geometria,
                    s.atributos_origem,
                    s.fonte_dado_id,
                    s.lote_ingestao_id,
                    s.registro_bruto_id,
                    s.hash_origem
                FROM (
                    SELECT
                        ? AS codigo_origem,
                        ? AS orgao_id,
                        ? AS nome,
                        ? AS tipo_area_protegida_id,
                        ? AS bioma_id,
                        ? AS ato_legal,
                        ? AS area_ha,
                        TO_CLOB(?) AS wkt,
                        TO_CLOB(?) AS atributos_origem,
                        ? AS fonte_dado_id,
                        ? AS lote_ingestao_id,
                        ? AS registro_bruto_id,
                        ? AS hash_origem
                    FROM dual
                ) s
            ) s ON (t.codigo_origem = s.codigo_origem AND t.orgao_id = s.orgao_id)
            WHEN MATCHED THEN
                UPDATE SET
                    t.nome                   = s.nome,
                    t.tipo_area_protegida_id = s.tipo_area_protegida_id,
                    t.bioma_id               = s.bioma_id,
                    t.ato_legal              = s.ato_legal,
                    t.area_ha                = s.area_ha,
                    t.geometria              = COALESCE(s.geometria, t.geometria),
                    t.atributos_origem       = s.atributos_origem,
                    t.fonte_dado_id          = s.fonte_dado_id,
                    t.lote_ingestao_id       = s.lote_ingestao_id,
                    t.registro_bruto_id      = s.registro_bruto_id,
                    t.data_processamento     = SYSTIMESTAMP,
                    t.hash_origem            = s.hash_origem
            WHEN NOT MATCHED THEN
                INSERT (
                    codigo_origem, orgao_id, nome, tipo_area_protegida_id,
                    bioma_id, ato_legal, area_ha, geometria,
                    atributos_origem, fonte_dado_id, lote_ingestao_id,
                    registro_bruto_id, data_processamento, hash_origem
                ) VALUES (
                    s.codigo_origem, s.orgao_id, s.nome, s.tipo_area_protegida_id,
                    s.bioma_id, s.ato_legal, s.area_ha, s.geometria,
                    s.atributos_origem, s.fonte_dado_id, s.lote_ingestao_id,
                    s.registro_bruto_id, SYSTIMESTAMP, s.hash_origem
                )
            """.formatted(silverSchema);

        executarMerge(sql,
                codigoOrigem,
                orgaoId,
                nome,
                tipoAreaId,
                biomaId,
                atoLegal,
                areaHa,
                geomWkt,
                atributosOrigem,
                reg != null && reg.getFonteDadoId() != null ? reg.getFonteDadoId() : 3L,
                reg != null && reg.getLoteIngestaoId() != null ? reg.getLoteIngestaoId() : 1L,
                reg != null && reg.getId() != null ? reg.getId() : 1L,
                reg != null ? reg.getHashRegistro() : null
        );
    }

    private void persistirAutoInfracao(RegistroAprovadoSilver apr) {
        Map<String, Object> attrs = apr.atributosTransformados();
        RegistroBruto reg = apr.registroBruto();

        String numeroAuto = extrairString(attrs, "numero_auto", "num_auto");
        Long orgaoId = resolverIdOrgao(attrs.get("orgao"), "IBAMA");
        Long municipioId = resolverIdMunicipio(attrs.get("municipio"), attrs.get("cod_municipio_ibge"));
        String cpfCnpj = extrairString(attrs, "cpf_cnpj_infrator", "cpf_cnpj");
        String nomeInfrator = extrairString(attrs, "nome_infrator", "infrator");
        Date dataAuto = extrairData(attrs, "data_auto");
        String numProcesso = extrairString(attrs, "num_processo");
        BigDecimal valorMulta = extrairBigDecimal(attrs, "valor_multa", "multa");
        String geomWkt = apr.geometriaWkt();
        String atributosOrigem = extrairPayloadOuJson(reg, attrs);

        String sql = """
            MERGE INTO %s.auto_infracao t
            USING (
                SELECT
                    s.numero_auto,
                    s.orgao_id,
                    s.municipio_id,
                    s.cpf_cnpj_infrator,
                    s.nome_infrator,
                    s.data_auto,
                    s.num_processo,
                    s.valor_multa,
                    CASE WHEN s.wkt IS NOT NULL THEN SDO_GEOMETRY(s.wkt, 4674) ELSE NULL END AS geometria,
                    s.atributos_origem,
                    s.fonte_dado_id,
                    s.lote_ingestao_id,
                    s.registro_bruto_id,
                    s.hash_origem
                FROM (
                    SELECT
                        ? AS numero_auto,
                        ? AS orgao_id,
                        ? AS municipio_id,
                        ? AS cpf_cnpj_infrator,
                        ? AS nome_infrator,
                        ? AS data_auto,
                        ? AS num_processo,
                        ? AS valor_multa,
                        TO_CLOB(?) AS wkt,
                        TO_CLOB(?) AS atributos_origem,
                        ? AS fonte_dado_id,
                        ? AS lote_ingestao_id,
                        ? AS registro_bruto_id,
                        ? AS hash_origem
                    FROM dual
                ) s
            ) s ON (t.numero_auto = s.numero_auto AND t.orgao_id = s.orgao_id)
            WHEN MATCHED THEN
                UPDATE SET
                    t.municipio_id        = s.municipio_id,
                    t.cpf_cnpj_infrator   = s.cpf_cnpj_infrator,
                    t.nome_infrator       = s.nome_infrator,
                    t.data_auto           = s.data_auto,
                    t.num_processo        = s.num_processo,
                    t.valor_multa         = s.valor_multa,
                    t.geometria           = COALESCE(s.geometria, t.geometria),
                    t.atributos_origem    = s.atributos_origem,
                    t.fonte_dado_id       = s.fonte_dado_id,
                    t.lote_ingestao_id    = s.lote_ingestao_id,
                    t.registro_bruto_id   = s.registro_bruto_id,
                    t.data_processamento  = SYSTIMESTAMP,
                    t.hash_origem         = s.hash_origem
            WHEN NOT MATCHED THEN
                INSERT (
                    numero_auto, orgao_id, municipio_id, cpf_cnpj_infrator,
                    nome_infrator, data_auto, num_processo, valor_multa,
                    geometria, atributos_origem, fonte_dado_id,
                    lote_ingestao_id, registro_bruto_id, data_processamento, hash_origem
                ) VALUES (
                    s.numero_auto, s.orgao_id, s.municipio_id, s.cpf_cnpj_infrator,
                    s.nome_infrator, s.data_auto, s.num_processo, s.valor_multa,
                    s.geometria, s.atributos_origem, s.fonte_dado_id,
                    s.lote_ingestao_id, s.registro_bruto_id, SYSTIMESTAMP, s.hash_origem
                )
            """.formatted(silverSchema);

        executarMerge(sql,
                numeroAuto,
                orgaoId,
                municipioId,
                cpfCnpj,
                nomeInfrator,
                dataAuto,
                numProcesso,
                valorMulta,
                geomWkt,
                atributosOrigem,
                reg != null && reg.getFonteDadoId() != null ? reg.getFonteDadoId() : 10L,
                reg != null && reg.getLoteIngestaoId() != null ? reg.getLoteIngestaoId() : 1L,
                reg != null && reg.getId() != null ? reg.getId() : 1L,
                reg != null ? reg.getHashRegistro() : null
        );
    }

    private void persistirEmbargoAmbiental(RegistroAprovadoSilver apr) {
        Map<String, Object> attrs = apr.atributosTransformados();
        RegistroBruto reg = apr.registroBruto();

        String numeroTermo = extrairString(attrs, "numero_termo", "num_tad", "numero_emb");
        Long orgaoId = resolverIdOrgao(attrs.get("orgao"), "IBAMA");
        Long municipioId = resolverIdMunicipio(attrs.get("municipio"), attrs.get("cod_municipio_ibge"));
        String cpfCnpj = extrairString(attrs, "cpf_cnpj", "cpf_cnpj_infrator");
        String nomeAutuado = extrairString(attrs, "nome_autuado", "autuado");
        Date dataEmbargo = extrairData(attrs, "data_embargo", "data_termo");
        BigDecimal areaEmbargadaHa = extrairBigDecimal(attrs, "area_embargada_ha", "area");
        String geomWkt = apr.geometriaWkt();
        String atributosOrigem = extrairPayloadOuJson(reg, attrs);

        String sql = """
            MERGE INTO %s.embargo_ambiental t
            USING (
                SELECT
                    s.numero_termo,
                    s.orgao_id,
                    s.municipio_id,
                    s.cpf_cnpj,
                    s.nome_autuado,
                    s.data_embargo,
                    s.area_embargada_ha,
                    CASE WHEN s.wkt IS NOT NULL THEN SDO_GEOMETRY(s.wkt, 4674) ELSE NULL END AS geometria,
                    s.atributos_origem,
                    s.fonte_dado_id,
                    s.lote_ingestao_id,
                    s.registro_bruto_id,
                    s.hash_origem
                FROM (
                    SELECT
                        ? AS numero_termo,
                        ? AS orgao_id,
                        ? AS municipio_id,
                        ? AS cpf_cnpj,
                        ? AS nome_autuado,
                        ? AS data_embargo,
                        ? AS area_embargada_ha,
                        TO_CLOB(?) AS wkt,
                        TO_CLOB(?) AS atributos_origem,
                        ? AS fonte_dado_id,
                        ? AS lote_ingestao_id,
                        ? AS registro_bruto_id,
                        ? AS hash_origem
                    FROM dual
                ) s
            ) s ON (t.numero_termo = s.numero_termo AND t.orgao_id = s.orgao_id)
            WHEN MATCHED THEN
                UPDATE SET
                    t.municipio_id        = s.municipio_id,
                    t.cpf_cnpj            = s.cpf_cnpj,
                    t.nome_autuado        = s.nome_autuado,
                    t.data_embargo        = s.data_embargo,
                    t.area_embargada_ha   = s.area_embargada_ha,
                    t.geometria           = COALESCE(s.geometria, t.geometria),
                    t.atributos_origem    = s.atributos_origem,
                    t.fonte_dado_id       = s.fonte_dado_id,
                    t.lote_ingestao_id    = s.lote_ingestao_id,
                    t.registro_bruto_id   = s.registro_bruto_id,
                    t.data_processamento  = SYSTIMESTAMP,
                    t.hash_origem         = s.hash_origem
            WHEN NOT MATCHED THEN
                INSERT (
                    numero_termo, orgao_id, municipio_id, cpf_cnpj,
                    nome_autuado, data_embargo, area_embargada_ha,
                    geometria, atributos_origem, fonte_dado_id,
                    lote_ingestao_id, registro_bruto_id, data_processamento, hash_origem
                ) VALUES (
                    s.numero_termo, s.orgao_id, s.municipio_id, s.cpf_cnpj,
                    s.nome_autuado, s.data_embargo, s.area_embargada_ha,
                    s.geometria, s.atributos_origem, s.fonte_dado_id,
                    s.lote_ingestao_id, s.registro_bruto_id, SYSTIMESTAMP, s.hash_origem
                )
            """.formatted(silverSchema);

        executarMerge(sql,
                numeroTermo,
                orgaoId,
                municipioId,
                cpfCnpj,
                nomeAutuado,
                dataEmbargo,
                areaEmbargadaHa,
                geomWkt,
                atributosOrigem,
                reg != null && reg.getFonteDadoId() != null ? reg.getFonteDadoId() : 5L,
                reg != null && reg.getLoteIngestaoId() != null ? reg.getLoteIngestaoId() : 1L,
                reg != null && reg.getId() != null ? reg.getId() : 1L,
                reg != null ? reg.getHashRegistro() : null
        );
    }

    private void persistirDesmatamento(RegistroAprovadoSilver apr) {
        Map<String, Object> attrs = apr.atributosTransformados();
        RegistroBruto reg = apr.registroBruto();

        Long fonteId = reg != null && reg.getFonteDadoId() != null ? reg.getFonteDadoId() : 4L;
        String identificadorOrigem = extrairString(attrs, "identificador_origem", "uuid", "fid", "id");
        if (identificadorOrigem == null && reg != null) {
            identificadorOrigem = "DESM-" + reg.getId();
        }
        Integer ano = extrairInteger(attrs, "ano");
        BigDecimal areaHa = extrairBigDecimal(attrs, "area_ha", "area");
        Date dataImagem = extrairData(attrs, "data_imagem");
        String geomWkt = apr.geometriaWkt();
        String atributosOrigem = extrairPayloadOuJson(reg, attrs);

        String sql = """
            MERGE INTO %s.desmatamento t
            USING (
                SELECT
                    s.fonte_dado_id,
                    s.identificador_origem,
                    s.ano,
                    s.area_ha,
                    s.data_imagem,
                    CASE WHEN s.wkt IS NOT NULL THEN SDO_GEOMETRY(s.wkt, 4674) ELSE NULL END AS geometria,
                    s.atributos_origem,
                    s.lote_ingestao_id,
                    s.registro_bruto_id,
                    s.hash_origem
                FROM (
                    SELECT
                        ? AS fonte_dado_id,
                        ? AS identificador_origem,
                        ? AS ano,
                        ? AS area_ha,
                        ? AS data_imagem,
                        TO_CLOB(?) AS wkt,
                        TO_CLOB(?) AS atributos_origem,
                        ? AS lote_ingestao_id,
                        ? AS registro_bruto_id,
                        ? AS hash_origem
                    FROM dual
                ) s
            ) s ON (t.fonte_dado_id = s.fonte_dado_id AND t.identificador_origem = s.identificador_origem)
            WHEN MATCHED THEN
                UPDATE SET
                    t.ano                = s.ano,
                    t.area_ha            = s.area_ha,
                    t.data_imagem        = s.data_imagem,
                    t.geometria          = COALESCE(s.geometria, t.geometria),
                    t.atributos_origem   = s.atributos_origem,
                    t.lote_ingestao_id   = s.lote_ingestao_id,
                    t.registro_bruto_id  = s.registro_bruto_id,
                    t.data_processamento = SYSTIMESTAMP,
                    t.hash_origem        = s.hash_origem
            WHEN NOT MATCHED THEN
                INSERT (
                    fonte_dado_id, identificador_origem, ano, area_ha,
                    data_imagem, geometria, atributos_origem,
                    lote_ingestao_id, registro_bruto_id, data_processamento, hash_origem
                ) VALUES (
                    s.fonte_dado_id, s.identificador_origem, s.ano, s.area_ha,
                    s.data_imagem, s.geometria, s.atributos_origem,
                    s.lote_ingestao_id, s.registro_bruto_id, SYSTIMESTAMP, s.hash_origem
                )
            """.formatted(silverSchema);

        executarMerge(sql,
                fonteId,
                identificadorOrigem,
                ano,
                areaHa,
                dataImagem,
                geomWkt,
                atributosOrigem,
                reg != null && reg.getLoteIngestaoId() != null ? reg.getLoteIngestaoId() : 1L,
                reg != null && reg.getId() != null ? reg.getId() : 1L,
                reg != null ? reg.getHashRegistro() : null
        );
    }

    private void persistirFocoCalor(RegistroAprovadoSilver apr) {
        Map<String, Object> attrs = apr.atributosTransformados();
        RegistroBruto reg = apr.registroBruto();

        Long fonteId = reg != null && reg.getFonteDadoId() != null ? reg.getFonteDadoId() : 6L;
        String identificadorOrigem = extrairString(attrs, "identificador_origem", "id", "objectid");
        if (identificadorOrigem == null && reg != null) {
            identificadorOrigem = "FOCO-" + reg.getId();
        }
        Timestamp dataHora = extrairTimestamp(attrs, "data_hora", "data");
        BigDecimal latitude = extrairBigDecimal(attrs, "latitude", "lat");
        BigDecimal longitude = extrairBigDecimal(attrs, "longitude", "lon");
        BigDecimal riscoFogo = extrairBigDecimal(attrs, "risco_fogo");
        Long municipioId = resolverIdMunicipio(attrs.get("municipio"), attrs.get("cod_municipio_ibge"));
        String geomWkt = apr.geometriaWkt();
        String atributosOrigem = extrairPayloadOuJson(reg, attrs);

        String sql = """
            MERGE INTO %s.foco_calor t
            USING (
                SELECT
                    s.fonte_dado_id,
                    s.identificador_origem,
                    s.data_hora,
                    s.municipio_id,
                    s.latitude,
                    s.longitude,
                    s.risco_fogo,
                    CASE WHEN s.wkt IS NOT NULL THEN SDO_GEOMETRY(s.wkt, 4674) ELSE NULL END AS geometria,
                    s.atributos_origem,
                    s.lote_ingestao_id,
                    s.registro_bruto_id,
                    s.hash_origem
                FROM (
                    SELECT
                        ? AS fonte_dado_id,
                        ? AS identificador_origem,
                        ? AS data_hora,
                        ? AS municipio_id,
                        ? AS latitude,
                        ? AS longitude,
                        ? AS risco_fogo,
                        TO_CLOB(?) AS wkt,
                        TO_CLOB(?) AS atributos_origem,
                        ? AS lote_ingestao_id,
                        ? AS registro_bruto_id,
                        ? AS hash_origem
                    FROM dual
                ) s
            ) s ON (t.fonte_dado_id = s.fonte_dado_id AND t.identificador_origem = s.identificador_origem)
            WHEN MATCHED THEN
                UPDATE SET
                    t.data_hora          = s.data_hora,
                    t.municipio_id       = s.municipio_id,
                    t.latitude           = s.latitude,
                    t.longitude          = s.longitude,
                    t.risco_fogo         = s.risco_fogo,
                    t.geometria          = COALESCE(s.geometria, t.geometria),
                    t.atributos_origem   = s.atributos_origem,
                    t.lote_ingestao_id   = s.lote_ingestao_id,
                    t.registro_bruto_id  = s.registro_bruto_id,
                    t.data_processamento = SYSTIMESTAMP,
                    t.hash_origem        = s.hash_origem
            WHEN NOT MATCHED THEN
                INSERT (
                    fonte_dado_id, identificador_origem, data_hora, municipio_id,
                    latitude, longitude, risco_fogo, geometria,
                    atributos_origem, lote_ingestao_id, registro_bruto_id,
                    data_processamento, hash_origem
                ) VALUES (
                    s.fonte_dado_id, s.identificador_origem, s.data_hora, s.municipio_id,
                    s.latitude, s.longitude, s.risco_fogo, s.geometria,
                    s.atributos_origem, s.lote_ingestao_id, s.registro_bruto_id,
                    SYSTIMESTAMP, s.hash_origem
                )
            """.formatted(silverSchema);

        executarMerge(sql,
                fonteId,
                identificadorOrigem,
                dataHora,
                municipioId,
                latitude,
                longitude,
                riscoFogo,
                geomWkt,
                atributosOrigem,
                reg != null && reg.getLoteIngestaoId() != null ? reg.getLoteIngestaoId() : 1L,
                reg != null && reg.getId() != null ? reg.getId() : 1L,
                reg != null ? reg.getHashRegistro() : null
        );
    }

    private void persistirGenerico(String entidade, RegistroAprovadoSilver apr) {
        log.warn("Entidade Silver '{}' não possui persistidor canônico especializado, delegando para imovel_rural.", entidade);
        persistirImovelRural(apr);
    }

    /**
     * Executa comandos MERGE associando os parâmetros diretamente ao PreparedStatement.
     * Evita que o Spring JDBC chame ps.getParameterMetaData() quando há parâmetros nulos,
     * contornando uma falha (ArrayIndexOutOfBoundsException) do parser de metadados do driver Oracle 23ai
     * em consultas MERGE contendo funções espaciais MDSYS.SDO_GEOMETRY.
     */
    private int executarMerge(String sql, Object... params) {
        return jdbcTemplate.execute(sql, (PreparedStatement ps) -> {
            List<java.sql.Clob> clobsToFree = new ArrayList<>();
            try {
                for (int i = 0; i < params.length; i++) {
                    Object val = params[i];
                    int paramIdx = i + 1;
                    if (val == null) {
                        ps.setNull(paramIdx, Types.NULL);
                    } else if (val instanceof java.sql.Date d) {
                        ps.setDate(paramIdx, d);
                    } else if (val instanceof java.sql.Timestamp ts) {
                        ps.setTimestamp(paramIdx, ts);
                    } else if (val instanceof java.math.BigDecimal bd) {
                        ps.setBigDecimal(paramIdx, bd);
                    } else if (val instanceof Long l) {
                        ps.setLong(paramIdx, l);
                    } else if (val instanceof Integer n) {
                        ps.setInt(paramIdx, n);
                    } else if (val instanceof Double d) {
                        ps.setDouble(paramIdx, d);
                    } else if (val instanceof java.sql.Clob c) {
                        ps.setClob(paramIdx, c);
                    } else if (val instanceof String s) {
                        if (s.length() > 2000) {
                            java.sql.Clob clob = ps.getConnection().createClob();
                            clob.setString(1, s);
                            clobsToFree.add(clob);
                            ps.setClob(paramIdx, clob);
                        } else {
                            ps.setString(paramIdx, s);
                        }
                    } else {
                        ps.setString(paramIdx, val.toString());
                    }
                }
                return ps.executeUpdate();
            } finally {
                for (java.sql.Clob c : clobsToFree) {
                    try {
                        c.free();
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Resolução de Referências e Lookups
    // -------------------------------------------------------------------------

    public Long resolverIdReferencia(String tabela, Object valor) {
        if (valor == null) return null;
        String valStr = valor.toString().trim();
        if (valStr.isEmpty()) return null;

        // Se já for numérico direto, retorna
        try {
            return Long.parseLong(valStr);
        } catch (NumberFormatException ignored) {}

        String tab = tabela.toLowerCase(Locale.ROOT);
        Map<String, Long> mapaTabela = refCache.computeIfAbsent(tab, k -> carregarMapaReferencia(tab));

        // 1. Busca direta (case-insensitive)
        Long id = mapaTabela.get(valStr.toUpperCase(Locale.ROOT));
        if (id != null) return id;

        // 2. Busca sem acentos
        String norm = ValidadorDominio.normalizarTexto(valStr);
        id = mapaTabela.get(norm);
        if (id != null) return id;

        // 3. Substituição de separadores
        if (norm.contains("_")) {
            id = mapaTabela.get(norm.replace('_', ' '));
            if (id != null) return id;
        }
        if (norm.contains(" ")) {
            id = mapaTabela.get(norm.replace(' ', '_'));
            if (id != null) return id;
        }
        if (norm.contains("-")) {
            id = mapaTabela.get(norm.replace('-', ' '));
            if (id != null) return id;
        }

        // 4. Apenas alfanuméricos
        String alnum = ValidadorDominio.normalizarApenasAlfanumerico(valStr);
        id = mapaTabela.get(alnum);
        if (id != null) return id;

        // 5. Sinônimos conhecidos e sub-estados oficiais
        String codCanonico = ValidadorDominio.resolverSinonimosConhecidosEstatico(valStr, tab);
        if (codCanonico != null) {
            id = mapaTabela.get(codCanonico);
            if (id != null) return id;
        }

        return null;
    }

    private Map<String, Long> carregarMapaReferencia(String tabela) {
        Map<String, Long> mapa = new HashMap<>();
        try {
            String sql = "SELECT id, codigo, descricao FROM " + silverSchema + "." + tabela + " WHERE ativo = 1";
            jdbcTemplate.query(sql, rs -> {
                long id = rs.getLong("id");
                String cod = rs.getString("codigo");
                String desc = rs.getString("descricao");
                if (cod != null) {
                    registrarNoMapaRef(mapa, cod, id);
                }
                if (desc != null) {
                    registrarNoMapaRef(mapa, desc, id);
                }
            });
        } catch (Exception e) {
            log.debug("Não foi possível carregar tabela de referência {}.{}: {}", silverSchema, tabela, e.getMessage());
        }
        return mapa;
    }

    private void registrarNoMapaRef(Map<String, Long> mapa, String chave, Long id) {
        if (chave == null || chave.isBlank()) return;
        String upper = chave.trim().toUpperCase(Locale.ROOT);
        mapa.putIfAbsent(upper, id);
        String norm = ValidadorDominio.normalizarTexto(chave);
        mapa.putIfAbsent(norm, id);
        if (norm.contains("_")) mapa.putIfAbsent(norm.replace('_', ' '), id);
        if (norm.contains(" ")) mapa.putIfAbsent(norm.replace(' ', '_'), id);
        if (norm.contains("-")) mapa.putIfAbsent(norm.replace('-', ' '), id);
        String alnum = ValidadorDominio.normalizarApenasAlfanumerico(chave);
        if (!alnum.isBlank()) mapa.putIfAbsent(alnum, id);
    }

    private Long resolverIdOrgao(Object valor, String fallbackCodigo) {
        Long id = resolverIdReferencia("orgao", valor);
        if (id != null) return id;
        return resolverIdReferencia("orgao", fallbackCodigo);
    }

    private Long resolverIdMunicipio(Object... valores) {
        for (Object v : valores) {
            if (v == null) continue;
            String str = v.toString().trim();
            if (str.isEmpty()) continue;
            try {
                // Tenta buscar por cod_ibge
                long cod = Long.parseLong(str);
                String sql = "SELECT id FROM " + silverSchema + ".municipio WHERE cod_ibge = ?";
                List<Long> ids = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong("id"), cod);
                if (!ids.isEmpty()) return ids.get(0);
            } catch (Exception ignored) {
                // Tenta por nome
                try {
                    String sql = "SELECT id FROM " + silverSchema + ".municipio WHERE UPPER(nome) = ?";
                    List<Long> ids = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong("id"), str.toUpperCase(Locale.ROOT));
                    if (!ids.isEmpty()) return ids.get(0);
                } catch (Exception e) {
                    log.debug("Erro ao consultar município {}: {}", str, e.getMessage());
                }
            }
        }
        return null;
    }

    private String normalizarNomeEntidade(String entidade) {
        if (entidade == null || entidade.isBlank()) {
            return "imovel_rural";
        }
        String e = entidade.trim().toLowerCase(Locale.ROOT);
        if (e.startsWith("q_")) {
            return e.substring(2);
        }
        return e;
    }

    private String extrairPayloadOuJson(RegistroBruto reg, Map<String, Object> attrs) {
        if (reg != null && reg.getPayload() != null && !reg.getPayload().isBlank()) {
            return reg.getPayload();
        }
        try {
            return objectMapper.writeValueAsString(attrs != null ? attrs : Collections.emptyMap());
        } catch (Exception e) {
            return "{}";
        }
    }

    private String extrairString(Map<String, Object> map, String... chaves) {
        if (map == null) return null;
        for (String c : chaves) {
            Object v = map.get(c);
            if (v != null && !v.toString().isBlank()) {
                return v.toString().trim();
            }
        }
        return null;
    }

    private Long extrairLong(Map<String, Object> map, String... chaves) {
        if (map == null) return null;
        for (String c : chaves) {
            Object v = map.get(c);
            if (v instanceof Number n) return n.longValue();
            if (v != null) {
                try {
                    return Long.parseLong(v.toString().trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private Integer extrairInteger(Map<String, Object> map, String... chaves) {
        Long l = extrairLong(map, chaves);
        return l != null ? l.intValue() : null;
    }

    private BigDecimal extrairBigDecimal(Map<String, Object> map, String... chaves) {
        if (map == null) return null;
        for (String c : chaves) {
            Object v = map.get(c);
            if (v instanceof BigDecimal bd) return bd;
            if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
            if (v != null) {
                try {
                    String s = v.toString().trim().replace(',', '.');
                    return new BigDecimal(s);
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private Date extrairData(Map<String, Object> map, String... chaves) {
        if (map == null) return null;
        for (String c : chaves) {
            Object v = map.get(c);
            if (v instanceof Date d) return d;
            if (v instanceof LocalDate ld) return Date.valueOf(ld);
            if (v != null) {
                String s = v.toString().trim();
                try {
                    if (s.length() >= 10) {
                        return Date.valueOf(s.substring(0, 10));
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private Timestamp extrairTimestamp(Map<String, Object> map, String... chaves) {
        if (map == null) return null;
        for (String c : chaves) {
            Object v = map.get(c);
            if (v instanceof Timestamp ts) return ts;
            if (v instanceof LocalDateTime ldt) return Timestamp.valueOf(ldt);
            if (v != null) {
                try {
                    return Timestamp.valueOf(v.toString().trim());
                } catch (Exception ignored) {}
            }
        }
        return null;
    }
}
