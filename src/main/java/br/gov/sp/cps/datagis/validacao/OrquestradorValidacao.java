package br.gov.sp.cps.datagis.validacao;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import br.gov.sp.cps.datagis.controle.MapeamentoColuna;
import br.gov.sp.cps.datagis.controle.MapeamentoColunaRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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

        List<RegistroAprovadoSilver> aprovados = new ArrayList<>();
        List<RegistroRejeitadoQuarentena> rejeitados = new ArrayList<>();

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
                for (MapeamentoColuna mapCol : mapeamentos) {
                String nomeColunaOrigem = mapCol.getColunaOrigem();
                String atributoDestino = mapCol.getAtributoDestino();
                Object valorBruto = extrairValor(payloadNode, reg, mapCol);
                Object valorAnterior = snapshotAnterior != null ? snapshotAnterior.get(atributoDestino) : null;

                ContextoValidacaoCampo contexto = mapCol.toContextoValidacao(valorAnterior);

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
                            rejeitados.add(new RegistroRejeitadoQuarentena(
                                    reg,
                                    resVal.tipoErro(),
                                    resVal.campoProblema(),
                                    resVal.valorRecebido(),
                                    resVal.motivoRejeicao()
                            ));
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
                } else {
                    atributosTransformados.put(atributoDestino, valorCorrente);
                }
            }
        }

            if (!falhou) {
                aprovados.add(new RegistroAprovadoSilver(reg, atributosTransformados, geometriaWktFinal));
            }
        }

        return new ResultadoOrquestracao(
                aprovados,
                rejeitados,
                descartadosDedup,
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

        JsonNode valNode = node.get(campoOrigem);
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
        JsonNode n = node.get(campoChave);
        return n != null && !n.isNull() ? n.asText().trim() : null;
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
            for (MapeamentoColuna mapCol : mapeamentos) {
                String atributoDestino = mapCol.getAtributoDestino();
                Object valorBruto = extrairValor(payloadNode, reg, mapCol);
                ContextoValidacaoCampo contexto = mapCol.toContextoValidacao(null);
                ResultadoValidacao resSanitizacao = sanitizador.validar(valorBruto, contexto);
                Object valorFinal = resSanitizacao.valorTransformado() != null ? resSanitizacao.valorTransformado() : valorBruto;

                if ("GEOMETRIA".equalsIgnoreCase(mapCol.getDestinoTipo())) {
                    if (valorFinal != null) {
                        geometriaWktFinal = valorFinal.toString();
                    }
                } else {
                    atributosTransformados.put(atributoDestino, valorFinal);
                }
            }
        }

        String geomSegura = sanitizarGeometria(geometriaWktFinal);
        return new RegistroAprovadoSilver(reg, atributosTransformados, geomSegura);
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
}

