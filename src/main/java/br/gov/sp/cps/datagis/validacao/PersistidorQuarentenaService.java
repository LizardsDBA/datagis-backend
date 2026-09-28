package br.gov.sp.cps.datagis.validacao;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import br.gov.sp.cps.datagis.controle.MapeamentoColuna;
import br.gov.sp.cps.datagis.controle.MapeamentoColunaRepository;
import java.nio.charset.StandardCharsets;
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
 * Responsável pela persistência física dos registros reprovados
 * nas tabelas espelho da camada de Quarentena (quarentena.q_*).
 * 
 * Preenche tanto os metadados de auditoria do erro quanto os campos de negócio
 * da entidade Silver a partir do mapeamento configurado da fonte e do payload JSON original.
 */
@Service
public class PersistidorQuarentenaService {

    private static final Logger log = LoggerFactory.getLogger(PersistidorQuarentenaService.class);

    private final JdbcTemplate jdbcTemplate;
    private final String quarentenaSchema;
    private final ObjectMapper objectMapper;
    private final MapeamentoColunaRepository mapeamentoRepository;
    private final Map<String, Long> tiposErroCache = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, String>> mapeamentoPorFonteCache = new ConcurrentHashMap<>();
    private volatile Long statusPendenteId = null;

    @Autowired
    public PersistidorQuarentenaService(
            JdbcTemplate jdbcTemplate,
            @Value("${spring.flyway.placeholders.quarentenaSchema:QUARENTENA}") String quarentenaSchema,
            ObjectMapper objectMapper,
            @Autowired(required = false) MapeamentoColunaRepository mapeamentoRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.quarentenaSchema = (quarentenaSchema != null && !quarentenaSchema.isBlank())
                ? quarentenaSchema.trim()
                : "QUARENTENA";
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.mapeamentoRepository = mapeamentoRepository;
    }

    public PersistidorQuarentenaService(
            JdbcTemplate jdbcTemplate,
            String quarentenaSchema,
            ObjectMapper objectMapper) {
        this(jdbcTemplate, quarentenaSchema, objectMapper, null);
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
        Map<String, String> mapCols = obterMapaAtributos(fonteId);

        String geomWkt = reg != null ? reg.getGeometriaWkt() : null;
        String campoProb = rej.campoProblema();
        String valRecebido = truncar(rej.valorRecebido(), 4000);
        String motivoRej = truncar(rej.motivoRejeicao(), 500);

        String tabelaLimpa = tabelaEspelho.toLowerCase(Locale.ROOT);

        if ("q_imovel_rural".equals(tabelaLimpa)) {
            String codImovel = truncar(extrairCampo(map, mapCols, "cod_imovel", "numero_car", "cod_recibo", "recibo", "cod_tema", "num_car", "car"), 120);
            String situacaoImovel = truncar(extrairCampo(map, mapCols, "situacao_imovel", "status_imovel", "situacao", "status"), 60);
            String condicaoImovel = truncar(extrairCampo(map, mapCols, "condicao_imovel", "condicao", "condicao_car"), 120);
            String tipoImovel = truncar(extrairCampo(map, mapCols, "tipo_imovel", "tipo", "tp_imovel"), 60);
            String codMunicipio = truncar(extrairCampo(map, mapCols, "cod_municipio", "cod_municipio_ibge", "municipio_ibge", "cod_ibge", "cd_mun"), 20);
            String dataRegistro = truncar(extrairCampo(map, mapCols, "data_registro", "dt_registro", "dat_cadastro", "dat_criacao"), 60);
            String area = truncar(extrairCampo(map, mapCols, "area_ha", "area", "area_total", "num_area"), 60);
            String modulosFiscais = truncar(extrairCampo(map, mapCols, "modulos_fiscais", "m_fiscal", "mod_fiscal", "num_modulo_fiscal"), 60);

            String sql = "INSERT INTO " + quarentenaSchema + ".q_imovel_rural (" +
                    "registro_bruto_id, lote_ingestao_id, fonte_dado_id, payload_original, tipo_erro_id, " +
                    "campo_problema, valor_recebido, motivo_rejeicao, status_analise_id, geometria_wkt, " +
                    "cod_imovel, situacao_imovel, condicao_imovel, tipo_imovel, cod_municipio, data_registro, area, modulos_fiscais" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            jdbcTemplate.update(sql, regId, loteId, fonteId, payloadJson, tipoErroId,
                    campoProb, valRecebido, motivoRej, statusId, geomWkt,
                    codImovel, situacaoImovel, condicaoImovel, tipoImovel, codMunicipio, dataRegistro, area, modulosFiscais);

        } else if ("q_auto_infracao".equals(tabelaLimpa)) {
            String numeroAuto = truncar(extrairCampo(map, mapCols, "numero_auto", "num_auto_infracao", "num_auto", "cd_original_auto_infracao", "numero_ai", "num_ai", "nu_auto", "ai"), 60);
            String orgao = truncar(extrairCampo(map, mapCols, "orgao", "unid_arrecadacao", "unid_controle", "unid_ordenadora", "origem"), 60);
            String tipoAuto = truncar(extrairCampo(map, mapCols, "tipo_auto_infracao", "tipo_auto", "tipo"), 80);
            String tipoInfracao = truncar(extrairCampo(map, mapCols, "tipo_infracao", "des_infracao", "tipo_infra"), 200);
            String tipoPessoa = truncar(extrairCampo(map, mapCols, "tipo_pessoa", "tp_pessoa_infrator"), 20);
            String statusAuto = truncar(extrairCampo(map, mapCols, "status_auto_infracao", "des_status_formulario", "ds_sit_auto_aie", "tp_ult_alteracao", "julgamento"), 60);
            String codMunicipio = truncar(extrairCampo(map, mapCols, "cod_municipio", "cod_municipio_ibge", "cod_ibge", "cd_mun"), 20);
            String cpfCnpjInfrator = truncar(extrairCampo(map, mapCols, "cpf_cnpj_infrator", "cpf_cnpj", "cpf", "cnpj", "nr_cpf_cnpj"), 40);
            String nomeInfrator = truncar(extrairCampo(map, mapCols, "nome_infrator", "nome_pessoa", "autuado", "infrator", "nome"), 300);
            String dataAuto = truncar(extrairCampo(map, mapCols, "data_auto", "dat_hora_auto_infracao", "dt_lancamento", "data", "dt_infracao"), 60);
            String numProcesso = truncar(extrairCampo(map, mapCols, "num_processo", "nu_processo_formatado", "processo", "nu_processo"), 60);
            String valorMulta = truncar(extrairCampo(map, mapCols, "valor_multa", "val_auto_infracao", "valor_mult", "multa", "val_multa"), 60);
            String houveEmbargo = truncar(extrairCampo(map, mapCols, "houve_embargo", "embargo"), 20);
            String houveApreensao = truncar(extrairCampo(map, mapCols, "houve_apreensao", "apreensao"), 20);
            String descricao = extrairCampo(map, mapCols, "descricao", "des_auto_infracao", "desc_ai", "ds_auto");

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
            String codigoOrigem = truncar(extrairCampo(map, mapCols, "codigo_origem", "cnuc", "terrai_cod", "id", "cod_uc", "objectid"), 120);
            String nome = truncar(extrairCampo(map, mapCols, "nome", "nome_uc", "terrai_nom", "nm_uc"), 300);
            String tipoArea = truncar(extrairCampo(map, mapCols, "tipo_area_protegida", "tipo_uc", "tipo_area", "modalidade"), 60);
            String categoria = truncar(extrairCampo(map, mapCols, "categoria_area_protegida", "categoria", "categoria_uc"), 80);
            String orgao = truncar(extrairCampo(map, mapCols, "orgao", "administracao", "jurisdicao"), 60);
            String bioma = truncar(extrairCampo(map, mapCols, "bioma", "ds_biomas_atingidos"), 60);
            String uf = truncar(extrairCampo(map, mapCols, "uf", "uf_sigla", "estado"), 60);
            String atoLegal = truncar(extrairCampo(map, mapCols, "ato_legal", "decreto", "legislacao"), 600);
            String area = truncar(extrairCampo(map, mapCols, "area_ha", "area", "area_total", "superficie"), 60);

            String sql = "INSERT INTO " + quarentenaSchema + ".q_area_protegida (" +
                    "registro_bruto_id, lote_ingestao_id, fonte_dado_id, payload_original, tipo_erro_id, " +
                    "campo_problema, valor_recebido, motivo_rejeicao, status_analise_id, geometria_wkt, " +
                    "codigo_origem, nome, tipo_area_protegida, categoria_area_protegida, orgao, bioma, uf, ato_legal, area" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            jdbcTemplate.update(sql, regId, loteId, fonteId, payloadJson, tipoErroId,
                    campoProb, valRecebido, motivoRej, statusId, geomWkt,
                    codigoOrigem, nome, tipoArea, categoria, orgao, bioma, uf, atoLegal, area);

        } else if ("q_embargo_ambiental".equals(tabelaLimpa)) {
            String numeroTermo = truncar(extrairCampo(map, mapCols, "numero_termo", "num_tad", "numero_emb", "num_termo", "tad", "termo", "num_termo_embargo"), 80);
            String orgao = truncar(extrairCampo(map, mapCols, "orgao", "unid_controle", "orgao_gestor"), 60);
            String tipoArea = truncar(extrairCampo(map, mapCols, "tipo_area_embargo", "tipo_infracao", "des_infracao", "tipo_embargo"), 60);
            String codMunicipio = truncar(extrairCampo(map, mapCols, "cod_municipio", "cod_municipio_ibge", "cod_ibge", "cd_mun"), 20);
            String cpfCnpj = truncar(extrairCampo(map, mapCols, "cpf_cnpj", "cpf_cnpj_infrator", "cpf", "cnpj"), 40);
            String nomeAutuado = truncar(extrairCampo(map, mapCols, "nome_autuado", "nome_infrator", "autuado", "infrator", "nome"), 300);
            String dataEmbargo = truncar(extrairCampo(map, mapCols, "data_embargo", "dat_hora_auto_infracao", "data_auto", "data", "dt_embargo"), 60);
            String numProcesso = truncar(extrairCampo(map, mapCols, "num_processo", "nu_processo_formatado", "processo", "nu_processo"), 60);
            String numAuto = truncar(extrairCampo(map, mapCols, "num_auto_infracao", "numero_auto", "numero_ai", "num_ai"), 60);
            String areaEmbargada = truncar(extrairCampo(map, mapCols, "area_embargada_ha", "area_embargada", "area_ha", "qt_area", "area"), 60);
            String descricao = extrairCampo(map, mapCols, "descricao", "des_infracao", "des_auto_infracao", "desc_sanc", "desc_infra", "motivo");

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
            String identificadorOrigem = truncar(extrairCampo(map, mapCols, "identificador_origem", "id", "foco_id"), 120);
            String dataHora = truncar(extrairCampo(map, mapCols, "data_hora", "data_hora_gmt", "dat_hora", "data"), 60);
            String satelite = truncar(extrairCampo(map, mapCols, "satelite"), 60);
            String bioma = truncar(extrairCampo(map, mapCols, "bioma"), 60);
            String codMunicipio = truncar(extrairCampo(map, mapCols, "cod_municipio", "cod_municipio_ibge", "municipio_id", "cod_ibge"), 20);
            String latitude = truncar(extrairCampo(map, mapCols, "latitude", "lat"), 60);
            String longitude = truncar(extrairCampo(map, mapCols, "longitude", "lon", "long"), 60);
            String riscoFogo = truncar(extrairCampo(map, mapCols, "risco_fogo"), 60);
            String diasSemChuva = truncar(extrairCampo(map, mapCols, "dias_sem_chuva", "numero_dias_sem_chuva"), 60);
            String precipitacao = truncar(extrairCampo(map, mapCols, "precipitacao"), 60);
            String frp = truncar(extrairCampo(map, mapCols, "frp"), 60);

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
            String identificadorOrigem = truncar(extrairCampo(map, mapCols, "identificador_origem", "uuid", "id"), 120);
            String ano = truncar(extrairCampo(map, mapCols, "ano", "year"), 20);
            String classeProdes = truncar(extrairCampo(map, mapCols, "classe_prodes", "class_name", "main_class"), 60);
            String satelite = truncar(extrairCampo(map, mapCols, "satelite", "satellite"), 60);
            String sensor = truncar(extrairCampo(map, mapCols, "sensor"), 60);
            String area = truncar(extrairCampo(map, mapCols, "area_ha", "area"), 60);
            String dataImagem = truncar(extrairCampo(map, mapCols, "data_imagem", "image_date", "view_date"), 60);

            String sql = "INSERT INTO " + quarentenaSchema + ".q_desmatamento (" +
                    "registro_bruto_id, lote_ingestao_id, fonte_dado_id, payload_original, tipo_erro_id, " +
                    "campo_problema, valor_recebido, motivo_rejeicao, status_analise_id, geometria_wkt, " +
                    "identificador_origem, ano, classe_prodes, satelite, sensor, area, data_imagem" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            jdbcTemplate.update(sql, regId, loteId, fonteId, payloadJson, tipoErroId,
                    campoProb, valRecebido, motivoRej, statusId, geomWkt,
                    identificadorOrigem, ano, classeProdes, satelite, sensor, area, dataImagem);

        } else if ("q_municipio".equals(tabelaLimpa)) {
            String codIbge = truncar(extrairCampo(map, mapCols, "cod_ibge", "CD_MUN", "cod_municipio", "codibge", "cd_geocmu"), 20);
            String nome = truncar(extrairCampo(map, mapCols, "nome", "NM_MUN", "nome_municipio", "municipio"), 200);
            String uf = truncar(extrairCampo(map, mapCols, "uf", "SIGLA_UF", "estado"), 40);
            String bioma = truncar(extrairCampo(map, mapCols, "bioma_predominante", "bioma"), 60);
            String areaKm2 = truncar(extrairCampo(map, mapCols, "area_km2", "AREA_KM2", "area"), 60);

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

    private Map<String, String> obterMapaAtributos(Long fonteDadoId) {
        if (fonteDadoId == null || mapeamentoRepository == null) {
            return Collections.emptyMap();
        }
        return mapeamentoPorFonteCache.computeIfAbsent(fonteDadoId, id -> {
            try {
                List<MapeamentoColuna> lista = mapeamentoRepository.findByFonteDadoId(id);
                if (lista == null || lista.isEmpty()) {
                    return Collections.emptyMap();
                }
                Map<String, String> map = new HashMap<>();
                for (MapeamentoColuna m : lista) {
                    if (m.getAtributoDestino() != null && m.getColunaOrigem() != null) {
                        map.put(m.getAtributoDestino().trim().toLowerCase(Locale.ROOT), m.getColunaOrigem().trim());
                    }
                }
                return map;
            } catch (Exception e) {
                log.warn("Falha ao carregar mapeamentos de coluna para fonte {}: {}", id, e.getMessage());
                return Collections.emptyMap();
            }
        });
    }

    private String extrairCampo(Map<String, Object> payloadMap, Map<String, String> mapCols, String atributoDestino, String... aliasesPadrao) {
        if (payloadMap == null || payloadMap.isEmpty()) return null;

        // 1. Se houver coluna de origem mapeada explicitamente em controle.mapeamento_coluna:
        if (mapCols != null && atributoDestino != null) {
            String colOrigem = mapCols.get(atributoDestino.toLowerCase(Locale.ROOT));
            if (colOrigem != null && !colOrigem.isBlank()) {
                String val = extrairTexto(payloadMap, colOrigem);
                if (val != null) return val;
            }
        }

        // 2. Busca pelo atributo destino canônico:
        if (atributoDestino != null) {
            String val = extrairTexto(payloadMap, atributoDestino);
            if (val != null) return val;
        }

        // 3. Busca pela lista de aliases padrão conhecidos:
        if (aliasesPadrao != null && aliasesPadrao.length > 0) {
            return extrairTexto(payloadMap, aliasesPadrao);
        }

        return null;
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
        byte[] bytes = texto.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return texto;
        }
        String s = texto;
        while (!s.isEmpty() && s.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            int excess = s.getBytes(StandardCharsets.UTF_8).length - maxBytes;
            int corte = Math.max(1, excess / 2);
            s = s.substring(0, Math.max(0, s.length() - corte));
        }
        return s;
    }
}
