package br.gov.sp.cps.datagis.validacao;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import br.gov.sp.cps.datagis.controle.MapeamentoColuna;
import br.gov.sp.cps.datagis.controle.MapeamentoColunaRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 2.9 / Fase 3: Orquestrador do Motor de Validações e Transição Bronze -> Silver / Quarentena.
 *
 * <p>Responsável por:
 * <ol>
 *   <li>Deduplicação preliminar de chaves naturais via {@link DeduplicadorRegistros};</li>
 *   <li>Carregar os mapeamentos de coluna e regras ativas para a fonte de dados;</li>
 *   <li>Executar a esteira completa campo a campo (Sanitizador -> Validadores especializados);</li>
 *   <li>Classificar os registros em {@link RegistroAprovadoSilver} ou {@link RegistroRejeitadoQuarentena}.</li>
 * </ol>
 */
@Component
public class OrquestradorValidacao {

    private static final Logger log = LoggerFactory.getLogger(OrquestradorValidacao.class);

    private final List<ValidadorCampo> validadores;
    private final SanitizadorEntrada sanitizador;
    private final DeduplicadorRegistros deduplicador;
    private final MapeamentoColunaRepository mapeamentoRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public OrquestradorValidacao(
            List<ValidadorCampo> validadores,
            SanitizadorEntrada sanitizador,
            DeduplicadorRegistros deduplicador,
            MapeamentoColunaRepository mapeamentoRepository,
            ObjectMapper objectMapper) {
        this.validadores = validadores != null ? validadores : List.of();
        this.sanitizador = sanitizador != null ? sanitizador : new SanitizadorEntrada();
        this.deduplicador = deduplicador != null ? deduplicador : new DeduplicadorRegistros();
        this.mapeamentoRepository = mapeamentoRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public OrquestradorValidacao(
            List<ValidadorCampo> validadores,
            SanitizadorEntrada sanitizador,
            DeduplicadorRegistros deduplicador,
            MapeamentoColunaRepository mapeamentoRepository) {
        this(validadores, sanitizador, deduplicador, mapeamentoRepository, new ObjectMapper());
    }

    /**
     * Orquestra a esteira completa para uma coleção de registros brutos de um lote.
     *
     * @param fonteDadoId ID da fonte no CONTROLE
     * @param registros lista de registros brutos extraídos no Bronze
     * @param campoChaveNatural nome da chave de negócio para deduplicação (ex.: {@code "cod_imovel"})
     * @param valoresAnterioresPorChave mapa opcional contendo o snapshot anterior da Silver para validação de regressão
     * @return resultado estruturado com aprovados, quarentena e duplicatas descartadas
     */
    public ResultadoOrquestracao orquestrarLote(
            Long fonteDadoId,
            List<RegistroBruto> registros,
            String campoChaveNatural,
            Map<String, Map<String, Object>> valoresAnterioresPorChave) {

        if (registros == null || registros.isEmpty()) {
            return new ResultadoOrquestracao(List.of(), List.of(), List.of(), 0, 0, 0, 0);
        }

        // 1. Deduplicação de registros (seleciona a versão mais recente para prosseguir)
        DeduplicadorRegistros.ResultadoDeduplicacao<RegistroBruto> resDedup =
                deduplicador.deduplicar(registros, campoChaveNatural);

        List<RegistroBruto> registrosParaValidar = resDedup.registrosPromovidos();
        List<DeduplicadorRegistros.DuplicataDescartada<RegistroBruto>> descartadosDedup =
                resDedup.duplicatasDescartadas();

        // 2. Carrega as regras de mapeamento de colunas configuradas para a fonte
        List<MapeamentoColuna> mapeamentos = mapeamentoRepository != null && fonteDadoId != null
                ? mapeamentoRepository.findByFonteDadoId(fonteDadoId)
                : Collections.emptyList();
        Map<String, List<MapeamentoColuna>> mapeamentosAgrupados = agruparMapeamentosPorDestino(mapeamentos);

        List<RegistroAprovadoSilver> aprovados = new ArrayList<>();
        List<RegistroRejeitadoQuarentena> rejeitados = new ArrayList<>();
        List<RegistroRejeitadoQuarentena> descartadosForaDoRecorte = new ArrayList<>();

        // 3. Validação individual de cada registro vencedor
        for (RegistroBruto reg : registrosParaValidar) {
            JsonNode payloadNode = parsearPayload(reg.getPayload());
            String chaveReg = extrairChave(payloadNode, campoChaveNatural);
            Map<String, Object> snapshotAnterior = valoresAnterioresPorChave != null && chaveReg != null
                    ? valoresAnterioresPorChave.get(chaveReg)
                    : null;

            Map<String, Object> atributosTransformados = new LinkedHashMap<>();
            String geometriaWktFinal = reg.getGeometriaWkt();
            boolean falhou = false;

            if (mapeamentos.isEmpty()) {
                // Fallback inteligente: quando mapeamento_coluna está vazia ou sem regras para esta fonte,
                // inspeciona diretamente as chaves do payload JSON para preservar dados e permitir fluxo.
                if (payloadNode != null) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = objectMapper.convertValue(payloadNode, Map.class);
                        if (map != null) {
                            for (Map.Entry<String, Object> entry : map.entrySet()) {
                                String k = entry.getKey();
                                Object v = entry.getValue();
                                if (k != null && ("geometry".equalsIgnoreCase(k) || "geom".equalsIgnoreCase(k) || "wkt".equalsIgnoreCase(k))) {
                                    if (geometriaWktFinal == null && v != null) {
                                        geometriaWktFinal = v.toString();
                                    }
                                } else if (k != null) {
                                    atributosTransformados.put(k, v);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Fallback de mapeamento vazio falhou ao ler payload: {}", e.getMessage());
                    }
                }
            } else {
                for (List<MapeamentoColuna> grupo : mapeamentosAgrupados.values()) {
                    ResolucaoValorCampo resolucao = resolverValorDoGrupo(payloadNode, reg, grupo);
                    MapeamentoColuna mapCol = resolucao.mapeamentoUtilizado();
                    Object valorBruto = resolucao.valor();
                    String atributoDestino = mapCol.getAtributoDestino();
                    Object valorAnterior = snapshotAnterior != null && atributoDestino != null
                            ? snapshotAnterior.get(atributoDestino)
                            : null;

                    ContextoValidacaoCampo contexto = prepararContexto(mapCol, valorAnterior);

                    // Executa sempre a sanitização de entrada primeiro
                    ResultadoValidacao resSanitizacao = sanitizador.validar(valorBruto, contexto);
                    if (!resSanitizacao.valido()) {
                        rejeitados.add(new RegistroRejeitadoQuarentena(
                                reg,
                                resSanitizacao.tipoErro(),
                                resSanitizacao.campoProblema(),
                                resSanitizacao.valorRecebido(),
                                resSanitizacao.motivoRejeicao()
                        ));
                        falhou = true;
                        break;
                    }

                    Object valorCorrente = resSanitizacao.valorTransformado();

                    // Executa a cadeia de validadores especializados
                    for (ValidadorCampo validador : validadores) {
                        if (validador == sanitizador) {
                            continue; // Sanitizador já executou
                        }

                        if (validador.suporta(contexto)) {
                            ResultadoValidacao resVal = validador.validar(valorCorrente, contexto);
                            if (!resVal.valido()) {
                                RegistroRejeitadoQuarentena descartado = new RegistroRejeitadoQuarentena(
                                        reg,
                                        resVal.tipoErro(),
                                        resVal.campoProblema(),
                                        resVal.valorRecebido(),
                                        resVal.motivoRejeicao()
                                );
                                if (ValidadorGeometria.ERRO_FORA_RECORTE_PR.equals(resVal.tipoErro())) {
                                    // Nao e inconsistencia de dado - a fonte e nacional/sem filtro
                                    // geografico (ex.: INPE Queimadas Brasil). Descarta sem ir pra
                                    // quarentena e sem bloquear o lote pela regra All-or-Nothing.
                                    descartadosForaDoRecorte.add(descartado);
                                } else {
                                    rejeitados.add(descartado);
                                }
                                falhou = true;
                                break;
                            }
                            if (resVal.valorTransformado() != null) {
                                valorCorrente = resVal.valorTransformado();
                            }
                        }
                    }

                    if (falhou) {
                        break;
                    }

                    // Se for mapeamento geométrico, atualiza a geometria canônica
                    if ("GEOMETRIA".equalsIgnoreCase(mapCol.getDestinoTipo())) {
                        if (valorCorrente != null) {
                            geometriaWktFinal = valorCorrente.toString();
                        }
                    } else if (atributoDestino != null) {
                        atributosTransformados.put(atributoDestino, valorCorrente);
                    }
                }
            }

            if (!falhou) {
                aprovados.add(new RegistroAprovadoSilver(reg, atributosTransformados, geometriaWktFinal));
            }
        }

        if (!descartadosForaDoRecorte.isEmpty()) {
            log.info("orquestrarLote fonte={}: {} registro(s) fora do recorte territorial (PR) descartados sem ir pra quarentena",
                    fonteDadoId, descartadosForaDoRecorte.size());
        }

        return new ResultadoOrquestracao(
                aprovados,
                rejeitados,
                descartadosDedup,
                descartadosForaDoRecorte,
                registros.size(),
                aprovados.size(),
                rejeitados.size(),
                descartadosDedup.size()
        );
    }

    public ResultadoOrquestracao orquestrarLote(Long fonteDadoId, List<RegistroBruto> registros, String campoChaveNatural) {
        return orquestrarLote(fonteDadoId, registros, campoChaveNatural, null);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private JsonNode parsearPayload(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.debug("Payload não é um JSON válido: {}", e.getMessage());
            return null;
        }
    }

    private Object extrairValor(JsonNode node, RegistroBruto reg, MapeamentoColuna mapCol) {
        String campoOrigem = mapCol.getColunaOrigem();

        if ("GEOMETRIA".equalsIgnoreCase(mapCol.getDestinoTipo()) && reg.getGeometriaWkt() != null) {
            return reg.getGeometriaWkt();
        }

        if (node == null || campoOrigem == null) {
            return null;
        }

        String campoLimpo = campoOrigem.trim();
        if (campoLimpo.isEmpty()) {
            return null;
        }

        JsonNode valNode = buscarNoPayload(node, campoLimpo);
        if (valNode == null || valNode.isNull()) {
            return null;
        }
        if (valNode.isNumber()) {
            return valNode.numberValue();
        }
        if (valNode.isBoolean()) {
            return valNode.booleanValue();
        }
        return valNode.asText();
    }

    private String extrairChave(JsonNode node, String campoChave) {
        if (node == null || campoChave == null) return null;
        JsonNode n = buscarNoPayload(node, campoChave.trim());
        return n != null && !n.isNull() ? n.asText().trim() : null;
    }

    private JsonNode buscarNoPayload(JsonNode root, String campo) {
        if (root == null || campo == null || campo.isBlank()) {
            return null;
        }

        // 1. Busca direta no nó raiz
        JsonNode n = root.get(campo);
        if (n != null && !n.isNull()) {
            return n;
        }

        // 2. Busca case-insensitive no nó raiz
        if (root.isObject()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = objectMapper.convertValue(root, Map.class);
                if (map != null) {
                    for (String key : map.keySet()) {
                        if (key != null && key.equalsIgnoreCase(campo)) {
                            JsonNode found = root.get(key);
                            if (found != null && !found.isNull()) {
                                return found;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3. Busca em contêineres GIS aninhados (GeoJSON properties, ArcGIS REST attributes)
        String[] containers = {"properties", "attributes"};
        for (String c : containers) {
            JsonNode container = root.get(c);
            if (container != null && container.isObject()) {
                JsonNode child = container.get(campo);
                if (child != null && !child.isNull()) {
                    return child;
                }
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> childMap = objectMapper.convertValue(container, Map.class);
                    if (childMap != null) {
                        for (String key : childMap.keySet()) {
                            if (key != null && key.equalsIgnoreCase(campo)) {
                                JsonNode found = container.get(key);
                                if (found != null && !found.isNull()) {
                                    return found;
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        return null;
    }

    /**
     * Transforma um RegistroBruto em RegistroAprovadoSilver aplicando todos os mapeamentos
     * configurados para a fonte (sanitização, mapeamento de campos e extração de geometria).
     * Garante que geometrias malformadas ou inválidas não quebrem o Oracle Spatial.
     */
    public RegistroAprovadoSilver transformarRegistro(Long fonteDadoId, RegistroBruto reg) {
        if (reg == null) return null;
        JsonNode payloadNode = parsearPayload(reg.getPayload());
        List<MapeamentoColuna> mapeamentos = mapeamentoRepository != null && fonteDadoId != null
                ? mapeamentoRepository.findByFonteDadoId(fonteDadoId)
                : Collections.emptyList();

        Map<String, Object> atributosTransformados = new LinkedHashMap<>();
        String geometriaWktFinal = reg.getGeometriaWkt();

        if (mapeamentos.isEmpty()) {
            if (payloadNode != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = objectMapper.convertValue(payloadNode, Map.class);
                    if (map != null) {
                        for (Map.Entry<String, Object> entry : map.entrySet()) {
                            String k = entry.getKey();
                            Object v = entry.getValue();
                            if (k != null && ("geometry".equalsIgnoreCase(k) || "geom".equalsIgnoreCase(k) || "wkt".equalsIgnoreCase(k))) {
                                if (geometriaWktFinal == null && v != null) {
                                    geometriaWktFinal = v.toString();
                                }
                            } else if (k != null) {
                                atributosTransformados.put(k, v);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        } else {
            Map<String, List<MapeamentoColuna>> mapeamentosAgrupados = agruparMapeamentosPorDestino(mapeamentos);
            for (List<MapeamentoColuna> grupo : mapeamentosAgrupados.values()) {
                ResolucaoValorCampo resolucao = resolverValorDoGrupo(payloadNode, reg, grupo);
                MapeamentoColuna mapCol = resolucao.mapeamentoUtilizado();
                Object valorBruto = resolucao.valor();
                String atributoDestino = mapCol.getAtributoDestino();
                ContextoValidacaoCampo contexto = prepararContexto(mapCol, null);

                ResultadoValidacao resSanitizacao = sanitizador.validar(valorBruto, contexto);
                Object valorFinal = resSanitizacao.valorTransformado() != null ? resSanitizacao.valorTransformado() : valorBruto;

                for (ValidadorCampo validador : validadores) {
                    if (validador == sanitizador) continue;
                    if (validador.suporta(contexto)) {
                        ResultadoValidacao resVal = validador.validar(valorFinal, contexto);
                        if (resVal.valido() && resVal.valorTransformado() != null) {
                            valorFinal = resVal.valorTransformado();
                        }
                    }
                }

                if ("GEOMETRIA".equalsIgnoreCase(mapCol.getDestinoTipo())) {
                    if (valorFinal != null) {
                        geometriaWktFinal = valorFinal.toString();
                    }
                } else if (atributoDestino != null) {
                    atributosTransformados.put(atributoDestino, valorFinal);
                }
            }
        }

        String geomSegura = sanitizarGeometria(geometriaWktFinal);
        return new RegistroAprovadoSilver(reg, atributosTransformados, geomSegura);
    }

    /**
     * Infere o nome canônico da tabela de referência de domínio (lookup/enum) a partir do nome do atributo de destino Silver.
     * OBS: Município e Auto de Infração NÃO pertencem a este método, pois são entidades canônicas validadas por Integridade Cruzada.
     */
    public static String inferirTabelaReferenciaCanonico(String atributoDestino) {
        if (atributoDestino == null || atributoDestino.isBlank()) {
            return null;
        }
        String a = atributoDestino.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (a) {
            case "situacao_imovel", "status_imovel" -> "situacao_imovel";
            case "condicao_imovel", "condicao" -> "condicao_imovel";
            case "tipo_imovel" -> "tipo_imovel";
            case "categoria_area_protegida", "categoria_uc" -> "categoria_area_protegida";
            case "orgao" -> "orgao";
            case "bioma", "bioma_predominante" -> "bioma";
            case "tipo_auto_infracao" -> "tipo_auto_infracao";
            case "tipo_infracao" -> "tipo_infracao";
            case "tipo_pessoa" -> "tipo_pessoa";
            case "status_auto_infracao" -> "status_auto_infracao";
            case "classe_prodes", "classes_prodes" -> "classe_prodes";
            case "satelite" -> "satelite";
            case "sensor" -> "sensor";
            case "tipo_area_embargo", "tipo_area_embrago" -> "tipo_area_embargo";
            case "unidade_federativa", "uf" -> "unidade_federativa";
            default -> null;
        };
    }

    /**
     * Infere o nome canônico da entidade Silver para Integridade Cruzada
     * a partir do nome do atributo de destino Silver (ex.: municipio, num_auto_infracao).
     */
    public static String inferirReferenciaEntidadeSilverCanonico(String atributoDestino) {
        if (atributoDestino == null || atributoDestino.isBlank()) {
            return null;
        }
        // "num_auto_infracao" removido da inferencia (LIZARDS-50) - achado ao vivo
        // reprocessando Embargos IBAMA/ICMBio apos corrigir o CPF: o campo
        // num_auto_infracao do EMBARGO nao e o mesmo identificador do Auto de
        // Infracao de verdade (testado contra o servico real do IAT/PR - Embargos
        // IBAMA e alfanumerico tipo "DBK2XZIO", Autos IBAMA e sempre numerico puro
        // tipo "368471" - formatos incompativeis, nunca vai casar). A config
        // explicita em controle.mapeamento_coluna (V071, referencia_entidade_silver
        // = NULL pras fontes 11/12) e quem deveria decidir isso - essa inferencia
        // por nome de atributo estava SOBRESCREVENDO a config explicita e
        // reaplicando a checagem mesmo com ela desligada no banco.
        String a = atributoDestino.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (a) {
            case "municipio", "cod_municipio_ibge", "municipio_id" -> "municipio";
            default -> null;
        };
    }

    /**
     * Valida um único registro individualmente contra a esteira de regras da fonte.
     * Devolve o resultado indicando se passou, com o objeto pronto para Silver ou erro de quarentena.
     */
    public ResultadoValidacaoRegistro validarRegistroIndividual(Long fonteDadoId, RegistroBruto reg) {
        ResultadoOrquestracao res = orquestrarLote(fonteDadoId, List.of(reg), null);
        if (!res.aprovados().isEmpty()) {
            return new ResultadoValidacaoRegistro(true, res.aprovados().get(0), null);
        } else {
            RegistroRejeitadoQuarentena rej = !res.rejeitados().isEmpty() ? res.rejeitados().get(0) : null;
            RegistroAprovadoSilver fallback = transformarRegistro(fonteDadoId, reg);
            return new ResultadoValidacaoRegistro(false, fallback, rej);
        }
    }

    public record ResultadoValidacaoRegistro(
            boolean valido,
            RegistroAprovadoSilver aprovado,
            RegistroRejeitadoQuarentena rejeitado
    ) {}

    private String sanitizarGeometria(String wkt) {
        if (wkt == null || wkt.isBlank()) return null;
        try {
            Geometry g = new WKTReader().read(wkt.trim());
            return (g != null && !g.isEmpty() && g.isValid()) ? wkt.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private record ResolucaoValorCampo(Object valor, MapeamentoColuna mapeamentoUtilizado) {}

    private Map<String, List<MapeamentoColuna>> agruparMapeamentosPorDestino(List<MapeamentoColuna> mapeamentos) {
        if (mapeamentos == null || mapeamentos.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<MapeamentoColuna>> grupos = new LinkedHashMap<>();
        int jsonIndex = 0;
        for (MapeamentoColuna mapCol : mapeamentos) {
            String chaveGrupo;
            if ("JSON".equalsIgnoreCase(mapCol.getDestinoTipo())) {
                chaveGrupo = "json:" + (mapCol.getColunaOrigem() != null ? mapCol.getColunaOrigem().toLowerCase(Locale.ROOT) : (jsonIndex++));
            } else if (mapCol.getAtributoDestino() != null && !mapCol.getAtributoDestino().isBlank()) {
                chaveGrupo = mapCol.getAtributoDestino().trim().toLowerCase(Locale.ROOT);
            } else {
                chaveGrupo = "origem:" + (mapCol.getColunaOrigem() != null ? mapCol.getColunaOrigem().toLowerCase(Locale.ROOT) : (jsonIndex++));
            }
            grupos.computeIfAbsent(chaveGrupo, k -> new ArrayList<>()).add(mapCol);
        }
        return grupos;
    }

    private ResolucaoValorCampo resolverValorDoGrupo(JsonNode payloadNode, RegistroBruto reg, List<MapeamentoColuna> grupo) {
        MapeamentoColuna primeiro = grupo.get(0);
        for (MapeamentoColuna mapCol : grupo) {
            Object v = extrairValor(payloadNode, reg, mapCol);
            if (v != null) {
                if (v instanceof String s) {
                    if (!s.trim().isEmpty()) {
                        return new ResolucaoValorCampo(v, mapCol);
                    }
                } else {
                    return new ResolucaoValorCampo(v, mapCol);
                }
            }
        }
        return new ResolucaoValorCampo(null, primeiro);
    }

    private ContextoValidacaoCampo prepararContexto(MapeamentoColuna mapCol, Object valorAnterior) {
        ContextoValidacaoCampo contexto = mapCol.toContextoValidacao(valorAnterior);

        // Defesa contra mapeamentos legados: municipio e auto_infracao pertencem a integridade cruzada Silver, nao a dominio
        if (contexto.tabelaReferencia() != null && !contexto.tabelaReferencia().isBlank()) {
            String tabNorm = contexto.tabelaReferencia().trim().toLowerCase(Locale.ROOT);
            if (tabNorm.contains(".")) {
                tabNorm = tabNorm.substring(tabNorm.lastIndexOf('.') + 1);
            }
            if ("municipio".equals(tabNorm) || "auto_infracao".equals(tabNorm)) {
                String refSilver = (contexto.referenciaEntidadeSilver() != null && !contexto.referenciaEntidadeSilver().isBlank())
                        ? contexto.referenciaEntidadeSilver()
                        : tabNorm;
                contexto = contexto.toBuilder()
                        .tabelaReferencia(null)
                        .referenciaEntidadeSilver(refSilver)
                        .build();
            } else if ("tipo_area_protegida".equals(tabNorm)) {
                contexto = contexto.toBuilder()
                        .tabelaReferencia(null)
                        .build();
            }
        }

        if (contexto.tabelaReferencia() == null || contexto.tabelaReferencia().isBlank()) {
            String tabInferida = inferirTabelaReferenciaCanonico(contexto.atributoDestino());
            if (tabInferida != null) {
                contexto = contexto.toBuilder().tabelaReferencia(tabInferida).build();
            }
        }

        if (contexto.referenciaEntidadeSilver() == null || contexto.referenciaEntidadeSilver().isBlank()) {
            String refSilverInferida = inferirReferenciaEntidadeSilverCanonico(contexto.atributoDestino());
            if (refSilverInferida != null) {
                contexto = contexto.toBuilder().referenciaEntidadeSilver(refSilverInferida).build();
            }
        }

        return contexto;
    }
}


