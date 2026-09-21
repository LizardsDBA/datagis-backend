package br.gov.sp.cps.datagis.validacao;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Responsável pela persistência física dos registros reprovados
 * nas tabelas espelho da camada de Quarentena (quarentena.q_*).
 */
@Service
public class PersistidorQuarentenaService {

    private static final Logger log = LoggerFactory.getLogger(PersistidorQuarentenaService.class);

    private final JdbcTemplate jdbcTemplate;
    private final String quarentenaSchema;
    private final ObjectMapper objectMapper;
    private final Map<String, Long> tiposErroCache = new ConcurrentHashMap<>();
    private volatile Long statusPendenteId = null;

    public PersistidorQuarentenaService(
            JdbcTemplate jdbcTemplate,
            @Value("${spring.flyway.placeholders.quarentenaSchema:QUARENTENA}") String quarentenaSchema,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.quarentenaSchema = (quarentenaSchema != null && !quarentenaSchema.isBlank())
                ? quarentenaSchema.trim()
                : "QUARENTENA";
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * Persiste uma lista de registros rejeitados na tabela espelho correspondente da entidade,
     * preenchendo tanto os metadados de auditoria quanto os campos de negócio da entidade Silver.
     */
    @Transactional
    public int persistirRejeitados(String entidade, List<RegistroRejeitadoQuarentena> rejeitados) {
        if (rejeitados == null || rejeitados.isEmpty()) {
            return 0;
        }

        String tabelaEspelho = resolverTabelaEspelho(entidade);
        Long statusId = obterStatusPendenteId();

        int inseridos = 0;
        for (RegistroRejeitadoQuarentena rej : rejeitados) {
            Long tipoErroId = obterTipoErroId(rej.tipoErro());
            try {
                inserirRegistroQuarentena(tabelaEspelho, rej, tipoErroId, statusId);
                inseridos++;
            } catch (Exception e) {
                log.error("Erro ao persistir registro bruto {} na quarentena {}: {}",
                        rej.registroBruto() != null ? rej.registroBruto().getId() : "null",
                        tabelaEspelho, e.getMessage(), e);
            }
        }

        log.info("Persistidos {} registros rejeitados em {}.{}", inseridos, quarentenaSchema, tabelaEspelho);
        return inseridos;
    }

    private void inserirRegistroQuarentena(
            String tabelaEspelho,
            RegistroRejeitadoQuarentena rej,
            Long tipoErroId,
            Long statusId) {

        RegistroBruto reg = rej.registroBruto();
        String payloadJson = reg != null ? reg.getPayload() : null;
        Map<String, Object> map = parsearPayloadComoMap(payloadJson);

        Long regId = reg != null ? reg.getId() : null;
        Long loteId = reg != null ? reg.getLoteIngestaoId() : null;
        Long fonteId = reg != null ? reg.getFonteDadoId() : null;
        String geomWkt = reg != null ? reg.getGeometriaWkt() : null;
        String campoProb = rej.campoProblema();
        String valRecebido = truncar(rej.valorRecebido(), 4000);
        String motivoRej = truncar(rej.motivoRejeicao(), 500);

        String tabelaLimpa = tabelaEspelho.toLowerCase(Locale.ROOT);

        if ("q_imovel_rural".equals(tabelaLimpa)) {
            String codImovel = truncar(extrairTexto(map, "cod_imovel", "numero_car", "cod_recibo", "recibo", "cod_tema"), 120);
            String situacaoImovel = truncar(extrairTexto(map, "status_imovel", "situacao_imovel", "situacao", "status"), 60);
            String condicaoImovel = truncar(extrairTexto(map, "condicao_imovel", "condicao", "condicao_car"), 120);
            String tipoImovel = truncar(extrairTexto(map, "tipo_imovel", "tipo"), 60);
            String codMunicipio = truncar(extrairTexto(map, "cod_municipio_ibge", "cod_municipio", "municipio_ibge", "cod_ibge"), 20);
            String dataRegistro = truncar(extrairTexto(map, "data_registro", "dt_registro", "dat_cadastro"), 60);
            String area = truncar(extrairTexto(map, "area", "area_ha", "area_total", "num_area"), 60);
            String modulosFiscais = truncar(extrairTexto(map, "m_fiscal", "modulos_fiscais", "mod_fiscal", "num_modulo_fiscal"), 60);

            String sql = "INSERT INTO " + quarentenaSchema + ".q_imovel_rural (" +
                    "registro_bruto_id, lote_ingestao_id, fonte_dado_id, payload_original, tipo_erro_id, " +
                    "campo_problema, valor_recebido, motivo_rejeicao, status_analise_id, geometria_wkt, " +
                    "cod_imovel, situacao_imovel, condicao_imovel, tipo_imovel, cod_municipio, data_registro, area, modulos_fiscais" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            jdbcTemplate.update(sql, regId, loteId, fonteId, payloadJson, tipoErroId,
                    campoProb, valRecebido, motivoRej, statusId, geomWkt,
                    codImovel, situacaoImovel, condicaoImovel, tipoImovel, codMunicipio, dataRegistro, area, modulosFiscais);

        } else if ("q_auto_infracao".equals(tabelaLimpa)) {
            String numeroAuto = truncar(extrairTexto(map, "num_auto_infracao", "numero_auto", "num_auto", "cd_original_auto_infracao", "numero_ai", "num_ai", "nu_auto", "ai"), 60);
            String orgao = truncar(extrairTexto(map, "orgao", "unid_arrecadacao", "unid_controle", "unid_ordenadora", "origem"), 60);
            String tipoAuto = truncar(extrairTexto(map, "tipo_auto", "tipo_auto_infracao", "tipo"), 80);
            String tipoInfracao = truncar(extrairTexto(map, "tipo_infracao", "des_infracao", "tipo_infra"), 200);
            String tipoPessoa = truncar(extrairTexto(map, "tp_pessoa_infrator", "tipo_pessoa"), 20);
            String statusAuto = truncar(extrairTexto(map, "des_status_formulario", "ds_sit_auto_aie", "status_auto_infracao", "tp_ult_alteracao", "julgamento"), 60);
            String codMunicipio = truncar(extrairTexto(map, "cod_municipio", "cod_municipio_ibge", "municipio", "cd_mun"), 20);
            String cpfCnpjInfrator = truncar(extrairTexto(map, "cpf_cnpj_infrator", "cpf_cnpj", "cpf", "cnpj", "nr_cpf_cnpj"), 40);
            String nomeInfrator = truncar(extrairTexto(map, "nome_infrator", "nome_pessoa", "autuado", "infrator", "nome"), 300);
            String dataAuto = truncar(extrairTexto(map, "dat_hora_auto_infracao", "data_auto", "dt_lancamento", "data", "dt_infracao"), 60);
            String numProcesso = truncar(extrairTexto(map, "num_processo", "nu_processo_formatado", "processo", "nu_processo"), 60);
            String valorMulta = truncar(extrairTexto(map, "val_auto_infracao", "valor_multa", "valor_mult", "multa", "val_multa"), 60);
            String houveEmbargo = truncar(extrairTexto(map, "houve_embargo", "embargo"), 20);
            String houveApreensao = truncar(extrairTexto(map, "houve_apreensao", "apreensao"), 20);
            String descricao = extrairTexto(map, "des_auto_infracao", "descricao", "desc_ai", "ds_auto");

            String sql = "INSERT INTO " + quarentenaSchema + ".q_auto_infracao (" +
                    "registro_bruto_id, lote_ingestao_id, fonte_dado_id, payload_original, tipo_erro_id, " +
                    "campo_problema, valor_recebido, motivo_rejeicao, status_analise_id, geometria_wkt, " +
                    "numero_auto, orgao, tipo_auto_infracao, tipo_infracao, tipo_pessoa, status_auto_infracao, " +
                    "cod_municipio, cpf_cnpj_infrator, nome_infrator, data_auto, num_processo, valor_multa, " +
                    "houve_embargo, houve_apreensao, descricao" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            jdbcTemplate.update(sql, regId, loteId, fonteId, payloadJson, tipoErroId,
                    campoProb, valRecebido, motivoRej, statusId, geomWkt,
                    numeroAuto, orgao, tipoAuto, tipoInfracao, tipoPessoa, statusAuto,
                    codMunicipio, cpfCnpjInfrator, nomeInfrator, dataAuto, numProcesso, valorMulta,
                    houveEmbargo, houveApreensao, descricao);

        } else if ("q_area_protegida".equals(tabelaLimpa)) {
            String codigoOrigem = truncar(extrairTexto(map, "codigo_origem", "cnuc", "terrai_cod", "id", "cod_uc", "objectid"), 120);
            String nome = truncar(extrairTexto(map, "nome", "nome_uc", "terrai_nom", "nm_uc"), 300);
            String tipoArea = truncar(extrairTexto(map, "tipo_area_protegida", "tipo_uc", "tipo_area", "modalidade"), 60);
            String categoria = truncar(extrairTexto(map, "categoria_area_protegida", "categoria", "categoria_uc"), 80);
            String orgao = truncar(extrairTexto(map, "orgao", "administracao", "jurisdicao"), 60);
            String bioma = truncar(extrairTexto(map, "bioma", "ds_biomas_atingidos"), 60);
            String uf = truncar(extrairTexto(map, "uf", "uf_sigla", "estado"), 60);
            String atoLegal = truncar(extrairTexto(map, "ato_legal", "decreto", "legislacao"), 600);
            String area = truncar(extrairTexto(map, "area", "area_ha", "area_total", "superficie"), 60);

            String sql = "INSERT INTO " + quarentenaSchema + ".q_area_protegida (" +
                    "registro_bruto_id, lote_ingestao_id, fonte_dado_id, payload_original, tipo_erro_id, " +
                    "campo_problema, valor_recebido, motivo_rejeicao, status_analise_id, geometria_wkt, " +
                    "codigo_origem, nome, tipo_area_protegida, categoria_area_protegida, orgao, bioma, uf, ato_legal, area" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            jdbcTemplate.update(sql, regId, loteId, fonteId, payloadJson, tipoErroId,
                    campoProb, valRecebido, motivoRej, statusId, geomWkt,
                    codigoOrigem, nome, tipoArea, categoria, orgao, bioma, uf, atoLegal, area);

        } else if ("q_embargo_ambiental".equals(tabelaLimpa)) {
            String numeroTermo = truncar(extrairTexto(map, "num_tad", "numero_termo", "numero_emb", "num_termo", "tad", "termo", "num_termo_embargo"), 80);
            String orgao = truncar(extrairTexto(map, "orgao", "unid_controle", "orgao_gestor"), 60);
            String tipoArea = truncar(extrairTexto(map, "tipo_area_embargo", "tipo_infracao", "des_infracao", "tipo_embargo"), 60);
            String codMunicipio = truncar(extrairTexto(map, "cod_municipio", "cod_municipio_ibge", "municipio"), 20);
            String cpfCnpj = truncar(extrairTexto(map, "cpf_cnpj", "cpf_cnpj_infrator", "cpf", "cnpj"), 40);
            String nomeAutuado = truncar(extrairTexto(map, "nome_autuado", "nome_infrator", "autuado", "infrator", "nome"), 300);
            String dataEmbargo = truncar(extrairTexto(map, "data_embargo", "dat_hora_auto_infracao", "data_auto", "data", "dt_embargo"), 60);
            String numProcesso = truncar(extrairTexto(map, "num_processo", "nu_processo_formatado", "processo", "nu_processo"), 60);
            String numAuto = truncar(extrairTexto(map, "num_auto_infracao", "numero_auto", "numero_ai", "num_ai"), 60);
            String areaEmbargada = truncar(extrairTexto(map, "area_embargada", "area_embargada_ha", "area_ha", "qt_area", "area"), 60);
            String descricao = extrairTexto(map, "descricao", "des_infracao", "des_auto_infracao", "motivo");

            String sql = "INSERT INTO " + quarentenaSchema + ".q_embargo_ambiental (" +
                    "registro_bruto_id, lote_ingestao_id, fonte_dado_id, payload_original, tipo_erro_id, " +
                    "campo_problema, valor_recebido, motivo_rejeicao, status_analise_id, geometria_wkt, " +
                    "numero_termo, orgao, tipo_area_embargo, cod_municipio, cpf_cnpj, nome_autuado, " +
                    "data_embargo, num_processo, num_auto_infracao, area_embargada, descricao" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            jdbcTemplate.update(sql, regId, loteId, fonteId, payloadJson, tipoErroId,
                    campoProb, valRecebido, motivoRej, statusId, geomWkt,
                    numeroTermo, orgao, tipoArea, codMunicipio, cpfCnpj, nomeAutuado,
                    dataEmbargo, numProcesso, numAuto, areaEmbargada, descricao);

        } else if ("q_foco_calor".equals(tabelaLimpa)) {
            String identificadorOrigem = truncar(extrairTexto(map, "identificador_origem", "id", "foco_id"), 120);
            String dataHora = truncar(extrairTexto(map, "data_hora", "data_hora_gmt", "dat_hora", "data"), 60);
            String satelite = truncar(extrairTexto(map, "satelite"), 60);
            String bioma = truncar(extrairTexto(map, "bioma"), 60);
            String codMunicipio = truncar(extrairTexto(map, "cod_municipio", "cod_municipio_ibge", "municipio_id"), 20);
            String latitude = truncar(extrairTexto(map, "latitude", "lat"), 60);
            String longitude = truncar(extrairTexto(map, "longitude", "lon", "long"), 60);
            String riscoFogo = truncar(extrairTexto(map, "risco_fogo"), 60);
            String diasSemChuva = truncar(extrairTexto(map, "dias_sem_chuva", "numero_dias_sem_chuva"), 60);
            String precipitacao = truncar(extrairTexto(map, "precipitacao"), 60);
            String frp = truncar(extrairTexto(map, "frp"), 60);

            String sql = "INSERT INTO " + quarentenaSchema + ".q_foco_calor (" +
                    "registro_bruto_id, lote_ingestao_id, fonte_dado_id, payload_original, tipo_erro_id, " +
                    "campo_problema, valor_recebido, motivo_rejeicao, status_analise_id, geometria_wkt, " +
                    "identificador_origem, data_hora, satelite, bioma, cod_municipio, " +
                    "latitude, longitude, risco_fogo, dias_sem_chuva, precipitacao, frp" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            jdbcTemplate.update(sql, regId, loteId, fonteId, payloadJson, tipoErroId,
                    campoProb, valRecebido, motivoRej, statusId, geomWkt,
                    identificadorOrigem, dataHora, satelite, bioma, codMunicipio,
                    latitude, longitude, riscoFogo, diasSemChuva, precipitacao, frp);

        } else if ("q_desmatamento".equals(tabelaLimpa)) {
            String identificadorOrigem = truncar(extrairTexto(map, "identificador_origem", "uuid", "id"), 120);
            String ano = truncar(extrairTexto(map, "ano", "year"), 20);
            String classeProdes = truncar(extrairTexto(map, "classe_prodes", "class_name", "main_class"), 60);
            String satelite = truncar(extrairTexto(map, "satelite", "satellite"), 60);
            String sensor = truncar(extrairTexto(map, "sensor"), 60);
            String area = truncar(extrairTexto(map, "area", "area_ha"), 60);
            String dataImagem = truncar(extrairTexto(map, "data_imagem", "image_date", "view_date"), 60);

            String sql = "INSERT INTO " + quarentenaSchema + ".q_desmatamento (" +
                    "registro_bruto_id, lote_ingestao_id, fonte_dado_id, payload_original, tipo_erro_id, " +
                    "campo_problema, valor_recebido, motivo_rejeicao, status_analise_id, geometria_wkt, " +
                    "identificador_origem, ano, classe_prodes, satelite, sensor, area, data_imagem" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            jdbcTemplate.update(sql, regId, loteId, fonteId, payloadJson, tipoErroId,
                    campoProb, valRecebido, motivoRej, statusId, geomWkt,
                    identificadorOrigem, ano, classeProdes, satelite, sensor, area, dataImagem);

        } else if ("q_municipio".equals(tabelaLimpa)) {
            String codIbge = truncar(extrairTexto(map, "CD_MUN", "cod_ibge", "cod_municipio", "codibge", "cd_geocmu"), 20);
            String nome = truncar(extrairTexto(map, "NM_MUN", "nome", "nome_municipio", "municipio"), 200);
            String uf = truncar(extrairTexto(map, "SIGLA_UF", "uf", "estado"), 40);
            String bioma = truncar(extrairTexto(map, "bioma_predominante", "bioma"), 60);
            String areaKm2 = truncar(extrairTexto(map, "area_km2", "AREA_KM2", "area"), 60);

            String sql = "INSERT INTO " + quarentenaSchema + ".q_municipio (" +
                    "registro_bruto_id, lote_ingestao_id, fonte_dado_id, payload_original, tipo_erro_id, " +
                    "campo_problema, valor_recebido, motivo_rejeicao, status_analise_id, geometria_wkt, " +
                    "cod_ibge, nome, uf, bioma_predominante, area_km2" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            jdbcTemplate.update(sql, regId, loteId, fonteId, payloadJson, tipoErroId,
                    campoProb, valRecebido, motivoRej, statusId, geomWkt,
                    codIbge, nome, uf, bioma, areaKm2);

        } else {
            String sql = "INSERT INTO " + quarentenaSchema + "." + tabelaEspelho + " (" +
                    "registro_bruto_id, lote_ingestao_id, fonte_dado_id, " +
                    "payload_original, tipo_erro_id, campo_problema, " +
                    "valor_recebido, motivo_rejeicao, status_analise_id, geometria_wkt" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            jdbcTemplate.update(sql, regId, loteId, fonteId, payloadJson, tipoErroId,
                    campoProb, valRecebido, motivoRej, statusId, geomWkt);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsearPayloadComoMap(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            return map != null ? map : Collections.emptyMap();
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private String extrairTexto(Map<String, Object> map, String... chaves) {
        if (map == null || map.isEmpty() || chaves == null) return null;

        Map<String, Object> props = null;
        Object propsObj = map.get("properties");
        if (propsObj instanceof Map) {
            props = (Map<String, Object>) propsObj;
        }

        for (String c : chaves) {
            Object v = map.get(c);
            if (v != null) {
                String s = v.toString().trim();
                if (!s.isBlank()) return s;
            }
            if (props != null) {
                Object vp = props.get(c);
                if (vp != null) {
                    String s = vp.toString().trim();
                    if (!s.isBlank()) return s;
                }
            }
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(c) && entry.getValue() != null) {
                    String s = entry.getValue().toString().trim();
                    if (!s.isBlank()) return s;
                }
            }
            if (props != null) {
                for (Map.Entry<String, Object> entry : props.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(c) && entry.getValue() != null) {
                        String s = entry.getValue().toString().trim();
                        if (!s.isBlank()) return s;
                    }
                }
            }
        }
        return null;
    }

    public String resolverTabelaEspelho(String entidade) {
        if (entidade == null || entidade.isBlank()) {
            return "q_imovel_rural";
        }
        String limpa = entidade.trim().toLowerCase(Locale.ROOT);
        if (limpa.startsWith("q_")) {
            return limpa;
        }
        return "q_" + limpa;
    }

    private Long obterTipoErroId(String codigo) {
        if (codigo == null || codigo.isBlank()) {
            return 1L; // Fallback
        }
        String codUpper = codigo.trim().toUpperCase(Locale.ROOT);
        return tiposErroCache.computeIfAbsent(codUpper, c -> {
            try {
                String sql = "SELECT id FROM " + quarentenaSchema + ".tipo_erro_quarentena WHERE codigo = ?";
                List<Long> ids = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong("id"), c);
                return ids.isEmpty() ? 1L : ids.get(0);
            } catch (Exception e) {
                log.warn("Falha ao buscar tipo_erro_id para {}: {}", c, e.getMessage());
                return 1L;
            }
        });
    }

    private Long obterStatusPendenteId() {
        if (statusPendenteId == null) {
            synchronized (this) {
                if (statusPendenteId == null) {
                    try {
                        String sql = "SELECT id FROM " + quarentenaSchema + ".status_analise_quarentena WHERE codigo = 'PENDENTE'";
                        List<Long> ids = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong("id"));
                        statusPendenteId = ids.isEmpty() ? 1L : ids.get(0);
                    } catch (Exception e) {
                        statusPendenteId = 1L;
                    }
                }
            }
        }
        return statusPendenteId;
    }

    private String truncar(String texto, int maxBytes) {
        if (texto == null) return null;
        byte[] bytes = texto.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return texto;
        }
        String s = texto;
        while (!s.isEmpty() && s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes) {
            int excess = s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length - maxBytes;
            int corte = Math.max(1, excess / 2);
            s = s.substring(0, Math.max(0, s.length() - corte));
        }
        return s;
    }
}
