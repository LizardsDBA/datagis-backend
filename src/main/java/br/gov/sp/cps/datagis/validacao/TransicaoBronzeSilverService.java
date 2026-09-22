package br.gov.sp.cps.datagis.validacao;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import br.gov.sp.cps.datagis.bronze.RegistroBrutoRepository;
import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.FonteDadoRepository;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import br.gov.sp.cps.datagis.controle.LoteIngestaoRepository;
import br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serviço central de Transição Automática Bronze -> Silver / Quarentena.
 *
 * <p>Aplica a <b>Regra Atômica de Integridade do Lote (All-or-Nothing)</b>:
 * <ul>
 *   <li><b>Lote 100% Válido (rejeitados = 0):</b> todos os registros aprovados são promovidos à Silver
 *       e o lote é marcado como {@code PROMOVIDO_SILVER};</li>
 *   <li><b>Lote com Qualquer Inconsistência (rejeitados &ge; 1):</b> bloqueio total da Silver (0 registros
 *       promovidos), os registros rejeitados são enviados à Quarentena com status {@code PENDENTE} e o
 *       lote é marcado como {@code RETIDO_QUARENTENA}, pausando o pipeline até decisão do analista;</li>
 *   <li><b>Liberação pelo Analista:</b> o lote retido é liberado e seus registros promovidos para a Silver;</li>
 *   <li><b>Descarte pelo Analista:</b> o lote retido é marcado como {@code DESCARTADO} e nada sobe para a Silver.</li>
 * </ul>
 */
@Service
public class TransicaoBronzeSilverService {

    private static final Logger log = LoggerFactory.getLogger(TransicaoBronzeSilverService.class);

    public static final String STATUS_RETIDO_QUARENTENA = "RETIDO_QUARENTENA";
    public static final String STATUS_PROMOVIDO_SILVER = "PROMOVIDO_SILVER";
    public static final String STATUS_DESCARTADO = "DESCARTADO";

    private final LoteIngestaoRepository loteIngestaoRepository;
    private final FonteDadoRepository fonteDadoRepository;
    private final RegistroBrutoRepository registroBrutoRepository;
    private final OrquestradorValidacao orquestradorValidacao;
    private final PersistidorSilverService persistidorSilverService;
    private final PersistidorQuarentenaService persistidorQuarentenaService;
    private final JdbcTemplate jdbcTemplate;
    private final String quarentenaSchema;

    public TransicaoBronzeSilverService(
            LoteIngestaoRepository loteIngestaoRepository,
            FonteDadoRepository fonteDadoRepository,
            RegistroBrutoRepository registroBrutoRepository,
            OrquestradorValidacao orquestradorValidacao,
            PersistidorSilverService persistidorSilverService,
            PersistidorQuarentenaService persistidorQuarentenaService,
            JdbcTemplate jdbcTemplate,
            @Value("${spring.flyway.placeholders.quarentenaSchema:QUARENTENA}") String quarentenaSchema) {
        this.loteIngestaoRepository = loteIngestaoRepository;
        this.fonteDadoRepository = fonteDadoRepository;
        this.registroBrutoRepository = registroBrutoRepository;
        this.orquestradorValidacao = orquestradorValidacao;
        this.persistidorSilverService = persistidorSilverService;
        this.persistidorQuarentenaService = persistidorQuarentenaService;
        this.jdbcTemplate = jdbcTemplate;
        this.quarentenaSchema = (quarentenaSchema != null && !quarentenaSchema.isBlank()) ? quarentenaSchema.trim() : "QUARENTENA";
    }

    /**
     * Executa a validação e transição atômica de um lote recém-gravado no Bronze.
     *
     * @param loteId ID do lote de ingestão
     * @return resultado estruturado da transição
     */
    @Transactional
    public ResultadoTransicao processarLote(Long loteId) {
        if (loteId == null) {
            return new ResultadoTransicao(null, "ERRO", 0, 0, 0, "ID do lote não informado.");
        }

        LoteIngestao lote = loteIngestaoRepository.findById(loteId).orElse(null);
        if (lote == null) {
            log.warn("Lote {} não encontrado para processamento da transição.", loteId);
            return new ResultadoTransicao(loteId, "NAO_ENCONTRADO", 0, 0, 0, "Lote não encontrado.");
        }

        // Se o lote não tem registros novos (ex.: SEM_MUDANCA), não há nada a validar
        if ("SEM_MUDANCA".equalsIgnoreCase(lote.getStatus()) || (lote.getQuantidadeRegistros() != null && lote.getQuantidadeRegistros() == 0)) {
            return new ResultadoTransicao(loteId, lote.getStatus(), 0, 0, 0, "Lote sem registros a processar.");
        }

        List<RegistroBruto> registros = registroBrutoRepository.findByLoteIngestaoIdOrderByIdAsc(loteId);
        if (registros == null || registros.isEmpty()) {
            log.info("Lote {} sem registros brutos associados na tabela registro_bruto.", loteId);
            return new ResultadoTransicao(loteId, lote.getStatus(), 0, 0, 0, "Nenhum registro bruto encontrado.");
        }

        FonteDado fonte = lote.getFonteDado();
        if (fonte == null && registros.get(0).getFonteDadoId() != null) {
            fonte = fonteDadoRepository.findById(registros.get(0).getFonteDadoId()).orElse(null);
        }

        String entidadeSilver = resolverEntidadeSilver(fonte);
        String campoChaveNatural = resolverChaveNatural(entidadeSilver, fonte);

        log.info("Iniciando esteira de validação para o lote {} (Fonte: {}, Entidade: {}, Registros: {})",
                loteId, fonte != null ? fonte.getNome() : "Desconhecida", entidadeSilver, registros.size());

        // Executa o motor de validações e deduplicação
        ResultadoOrquestracao resOrquestracao = orquestradorValidacao.orquestrarLote(
                fonte != null ? fonte.getId() : null,
                registros,
                campoChaveNatural,
                null
        );

        // REGRA ATÔMICA ALL-OR-NOTHING:
        // Se houver qualquer registro reprovado (>= 1), o lote inteiro é retido e ZERO registros vão para a Silver.
        if (resOrquestracao.temErros()) {
            int qtdErros = resOrquestracao.rejeitados().size();
            log.warn("LOTE {} BLOQUEADO: {} inconsistência(s) detectada(s). Regra All-or-Nothing aplicada: 0 registros promovidos para a Silver.",
                    loteId, qtdErros);

            // Persiste inconsistências na Quarentena
            persistidorQuarentenaService.persistirRejeitados(entidadeSilver, resOrquestracao.rejeitados());

            // Atualiza status do lote
            lote.setStatus(STATUS_RETIDO_QUARENTENA);
            loteIngestaoRepository.save(lote);

            return new ResultadoTransicao(
                    loteId,
                    STATUS_RETIDO_QUARENTENA,
                    0,
                    qtdErros,
                    resOrquestracao.descartadosDedup().size(),
                    "Lote retido na Quarentena com " + qtdErros + " inconsistência(s). 0 registros enviados à Silver."
            );
        }

        // Lote 100% íntegro: promove todos os registros aprovados diretamente para a Silver
        int qtdAprovados = resOrquestracao.aprovados().size();
        log.info("LOTE {} APROVADO: 100% íntegro. Promovendo {} registro(s) para a Silver ({}.{}).",
                loteId, qtdAprovados, "SILVER", entidadeSilver);

        persistidorSilverService.persistirAprovados(entidadeSilver, resOrquestracao.aprovados());

        lote.setStatus(STATUS_PROMOVIDO_SILVER);
        loteIngestaoRepository.save(lote);

        return new ResultadoTransicao(
                loteId,
                STATUS_PROMOVIDO_SILVER,
                qtdAprovados,
                0,
                resOrquestracao.descartadosDedup().size(),
                "Lote integralmente aprovado e promovido à Silver."
        );
    }

    /**
     * Liberação manual de um lote retido em quarentena após análise/correção pelo analista.
     * Promove todos os registros do lote (aprovados, rejeitados reprocessados/sanitizados e itens da quarentena)
     * para a camada Silver e atualiza a quarentena.
     */
    @Transactional
    public boolean liberarLoteParaSilver(Long loteId, Long analistaId) {
        LoteIngestao lote = loteIngestaoRepository.findById(loteId).orElse(null);
        if (lote == null) {
            log.warn("Lote {} não encontrado para liberação.", loteId);
            return false;
        }

        FonteDado fonte = lote.getFonteDado();
        String entidadeSilver = resolverEntidadeSilver(fonte);
        String campoChaveNatural = resolverChaveNatural(entidadeSilver, fonte);
        Long fonteDadoId = fonte != null ? fonte.getId() : null;

        // 1. Sincroniza correções manuais de payload da Quarentena de volta para o Bronze se houver
        sincronizarCorrecoesQuarentenaParaBronze(loteId, entidadeSilver);

        // 2. Busca registros brutos associados ao lote
        List<RegistroBruto> registros = registroBrutoRepository.findByLoteIngestaoIdOrderByIdAsc(loteId);

        List<RegistroAprovadoSilver> todosParaPersistir = new ArrayList<>();

        if (!registros.isEmpty()) {
            // Executa esteira de validação e deduplicação
            ResultadoOrquestracao res = orquestradorValidacao.orquestrarLote(
                    fonteDadoId,
                    registros,
                    campoChaveNatural
            );

            // Adiciona os aprovados
            todosParaPersistir.addAll(res.aprovados());

            // Para os registros rejeitados: como a ação do analista é LIBERAR O LOTE,
            // transformamos os rejeitados aplicando sanitização de campos e de geometria
            // para que subam à Silver sem falhar constraints nem o Oracle Spatial
            for (RegistroRejeitadoQuarentena rej : res.rejeitados()) {
                if (rej.registroBruto() != null) {
                    RegistroAprovadoSilver aprRej = orquestradorValidacao.transformarRegistro(fonteDadoId, rej.registroBruto());
                    if (aprRej != null) {
                        todosParaPersistir.add(aprRej);
                    }
                }
            }
        }

        // 3. Inclui eventuais registros da Quarentena que não possuam registro_bruto_id (ex.: registros simulados/seed)
        List<RegistroAprovadoSilver> itensQuarentenaSemBronze = extrairItensQuarentenaSemBronze(loteId, entidadeSilver, fonteDadoId);
        todosParaPersistir.addAll(itensQuarentenaSemBronze);

        if (todosParaPersistir.isEmpty()) {
            log.warn("Lote {} sem registros brutos ou de quarentena a promover.", loteId);
            return false;
        }

        // 4. Promove todos os registros reunidos para a Silver
        persistidorSilverService.persistirAprovados(entidadeSilver, todosParaPersistir);

        // 5. Atualiza registros da quarentena para CORRIGIDO_REPROCESSADO (status_analise_id = 3)
        atualizarStatusQuarentenaPorLote(loteId, "CORRIGIDO_REPROCESSADO", analistaId, null);

        // 6. Atualiza status do lote
        lote.setStatus(STATUS_PROMOVIDO_SILVER);
        loteIngestaoRepository.save(lote);

        log.info("Lote {} retido foi liberado pelo analista {} e promovido à Silver ({} registros no total).",
                loteId, analistaId, todosParaPersistir.size());
        return true;
    }

    /**
     * Promove um registro individual retido na Quarentena diretamente para a Silver.
     * Permite ao operador aplicar correção manual de payload JSON antes da promoção.
     */
    @Transactional
    public boolean promoverRegistroQuarentenaParaSilver(String entidade, Long quarentenaId, AcaoQuarentenaRequest request) {
        if (quarentenaId == null) {
            return false;
        }

        String entSilver = normalizarEntidadeSilver(entidade);
        String tabEspelho = "q_" + entSilver;

        // 1. Carrega o registro da tabela espelho de quarentena
        String sql = "SELECT q.id, q.fonte_dado_id, q.lote_ingestao_id, q.registro_bruto_id, " +
                     "q.geometria_wkt, JSON_SERIALIZE(q.payload_original RETURNING CLOB) AS payload_original " +
                     "FROM " + quarentenaSchema + "." + tabEspelho + " q WHERE q.id = ?";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, quarentenaId);
        if (rows.isEmpty()) {
            log.warn("Registro de quarentena {} não encontrado na tabela {}.{}", quarentenaId, quarentenaSchema, tabEspelho);
            return false;
        }

        Map<String, Object> row = rows.get(0);
        Long fonteDadoId = row.get("fonte_dado_id") != null ? ((Number) row.get("fonte_dado_id")).longValue() : null;
        Long loteIngestaoId = row.get("lote_ingestao_id") != null ? ((Number) row.get("lote_ingestao_id")).longValue() : null;
        Long regBrutoId = row.get("registro_bruto_id") != null ? ((Number) row.get("registro_bruto_id")).longValue() : null;
        String geomWkt = row.get("geometria_wkt") != null ? row.get("geometria_wkt").toString() : null;
        String payloadOriginal = row.get("payload_original") != null ? row.get("payload_original").toString() : null;

        // 2. Determina o payload efetivo (se o operador editou no modal, usa o corrigido)
        String payloadEfetivo = (request != null && request.payloadCorrigido() != null && !request.payloadCorrigido().trim().isBlank())
                ? request.payloadCorrigido().trim()
                : payloadOriginal;

        // Se corrigido, atualiza a quarentena com o novo payload
        if (request != null && request.payloadCorrigido() != null && !request.payloadCorrigido().trim().isBlank()) {
            try {
                jdbcTemplate.update(
                        "UPDATE " + quarentenaSchema + "." + tabEspelho + " SET payload_original = ? WHERE id = ?",
                        payloadEfetivo, quarentenaId
                );
            } catch (Exception e) {
                log.debug("Aviso ao atualizar payload na quarentena {}.{}: {}", quarentenaSchema, tabEspelho, e.getMessage());
            }
        }

        // 3. Atualiza ou instancia o RegistroBruto
        RegistroBruto reg = null;
        if (regBrutoId != null) {
            reg = registroBrutoRepository.findById(regBrutoId).orElse(null);
            if (reg != null) {
                if (payloadEfetivo != null && !payloadEfetivo.equals(reg.getPayload())) {
                    reg.setPayload(payloadEfetivo);
                    registroBrutoRepository.save(reg);
                }
                if (geomWkt != null && (reg.getGeometriaWkt() == null || reg.getGeometriaWkt().isBlank())) {
                    reg.setGeometriaWkt(geomWkt);
                }
            }
        }

        if (reg == null) {
            reg = new RegistroBruto();
            reg.setId(regBrutoId != null ? regBrutoId : quarentenaId);
            reg.setFonteDadoId(fonteDadoId != null ? fonteDadoId : 1L);
            reg.setLoteIngestaoId(loteIngestaoId != null ? loteIngestaoId : 1L);
            reg.setPayload(payloadEfetivo);
            reg.setGeometriaWkt(geomWkt);
        }

        // 4. Transforma o registro aplicando mapeamentos, sanitização de atributos e geometria
        RegistroAprovadoSilver apr = orquestradorValidacao.transformarRegistro(fonteDadoId, reg);

        // 5. Persiste na camada Silver
        persistidorSilverService.persistirAprovados(entSilver, List.of(apr));

        // 6. Atualiza o status do registro na Quarentena para CORRIGIDO_REPROCESSADO
        Long analistaId = request != null ? request.analistaId() : null;
        String sqlUpdateStatus = "UPDATE " + quarentenaSchema + "." + tabEspelho +
                " SET status_analise_id = (SELECT id FROM " + quarentenaSchema + ".status_analise_quarentena WHERE codigo = 'CORRIGIDO_REPROCESSADO'), " +
                " analista_responsavel_id = ?, data_resolucao = SYSTIMESTAMP " +
                " WHERE id = ?";
        jdbcTemplate.update(sqlUpdateStatus, analistaId, quarentenaId);

        // 7. Se pertencer a um lote, verifica se todas as pendências foram concluídas
        if (loteIngestaoId != null) {
            verificarEAtualizarStatusLoteAposResolucao(loteIngestaoId);
        }

        log.info("Registro de quarentena {} ({}) promovido com sucesso à Silver ({}) pelo analista {}.",
                quarentenaId, tabEspelho, entSilver, analistaId);
        return true;
    }

    /**
     * Descarte total de um lote retido em quarentena pelo analista.
     * Marca o lote e os registros da quarentena como DESCARTADO. Nenhum registro sobe para a Silver.
     */
    @Transactional
    public boolean descartarLote(Long loteId, String motivo, Long analistaId) {
        LoteIngestao lote = loteIngestaoRepository.findById(loteId).orElse(null);
        if (lote == null) {
            return false;
        }

        atualizarStatusQuarentenaPorLote(loteId, "DESCARTADO", analistaId, motivo != null ? motivo : "Descartado pelo analista");

        lote.setStatus(STATUS_DESCARTADO);
        loteIngestaoRepository.save(lote);

        log.info("Lote {} foi descartado pelo analista {}. 0 registros na Silver.", loteId, analistaId);
        return true;
    }

    private void atualizarStatusQuarentenaPorLote(Long loteId, String codigoStatus, Long analistaId, String motivoExtra) {
        String[] tabelas = {
            "q_imovel_rural", "q_area_protegida", "q_auto_infracao",
            "q_embargo_ambiental", "q_desmatamento", "q_foco_calor", "q_municipio"
        };
        for (String tab : tabelas) {
            try {
                if (motivoExtra != null && !motivoExtra.isBlank()) {
                    String sql = "UPDATE " + quarentenaSchema + "." + tab +
                            " SET status_analise_id = (SELECT id FROM " + quarentenaSchema + ".status_analise_quarentena WHERE codigo = ?), " +
                            " data_resolucao = SYSTIMESTAMP, analista_responsavel_id = ?, " +
                            " motivo_rejeicao = SUBSTR(COALESCE(motivo_rejeicao, '') || ' | Descartado: ' || ?, 1, 500) " +
                            " WHERE lote_ingestao_id = ?";
                    int count = jdbcTemplate.update(sql, codigoStatus, analistaId, motivoExtra, loteId);
                    if (count > 0) {
                        log.info("Atualizados {} registros em {}.{} para status {} no lote {}", count, quarentenaSchema, tab, codigoStatus, loteId);
                    }
                } else {
                    String sql = "UPDATE " + quarentenaSchema + "." + tab +
                            " SET status_analise_id = (SELECT id FROM " + quarentenaSchema + ".status_analise_quarentena WHERE codigo = ?), " +
                            " data_resolucao = SYSTIMESTAMP, analista_responsavel_id = ? " +
                            " WHERE lote_ingestao_id = ?";
                    int count = jdbcTemplate.update(sql, codigoStatus, analistaId, loteId);
                    if (count > 0) {
                        log.info("Atualizados {} registros em {}.{} para status {} no lote {}", count, quarentenaSchema, tab, codigoStatus, loteId);
                    }
                }
            } catch (Exception e) {
                log.debug("Tabela {}.{} não afetada pelo lote {}: {}", quarentenaSchema, tab, loteId, e.getMessage());
            }
        }
    }

    private void sincronizarCorrecoesQuarentenaParaBronze(Long loteId, String entidadeSilver) {
        String tabEspelho = "q_" + normalizarEntidadeSilver(entidadeSilver);
        try {
            String sql = "SELECT registro_bruto_id, JSON_SERIALIZE(payload_original RETURNING CLOB) AS payload_original " +
                         "FROM " + quarentenaSchema + "." + tabEspelho + " " +
                         "WHERE lote_ingestao_id = ? AND registro_bruto_id IS NOT NULL";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, loteId);
            for (Map<String, Object> r : rows) {
                if (r.get("registro_bruto_id") == null) continue;
                Long regId = ((Number) r.get("registro_bruto_id")).longValue();
                String payload = r.get("payload_original") != null ? r.get("payload_original").toString() : null;
                if (payload != null && !payload.isBlank()) {
                    registroBrutoRepository.findById(regId).ifPresent(reg -> {
                        if (!payload.equals(reg.getPayload())) {
                            reg.setPayload(payload);
                            registroBrutoRepository.save(reg);
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.debug("Aviso ao sincronizar correções da quarentena {}.{}: {}", quarentenaSchema, tabEspelho, e.getMessage());
        }
    }

    private List<RegistroAprovadoSilver> extrairItensQuarentenaSemBronze(Long loteId, String entidadeSilver, Long fonteDadoId) {
        String tabEspelho = "q_" + normalizarEntidadeSilver(entidadeSilver);
        List<RegistroAprovadoSilver> lista = new ArrayList<>();
        try {
            String sql = "SELECT id, fonte_dado_id, lote_ingestao_id, geometria_wkt, " +
                         "JSON_SERIALIZE(payload_original RETURNING CLOB) AS payload_original " +
                         "FROM " + quarentenaSchema + "." + tabEspelho + " " +
                         "WHERE lote_ingestao_id = ? AND registro_bruto_id IS NULL";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, loteId);
            for (Map<String, Object> row : rows) {
                Long qId = ((Number) row.get("id")).longValue();
                String payload = row.get("payload_original") != null ? row.get("payload_original").toString() : null;
                String geomWkt = row.get("geometria_wkt") != null ? row.get("geometria_wkt").toString() : null;

                RegistroBruto reg = new RegistroBruto();
                reg.setId(qId);
                reg.setFonteDadoId(fonteDadoId != null ? fonteDadoId : 1L);
                reg.setLoteIngestaoId(loteId);
                reg.setPayload(payload);
                reg.setGeometriaWkt(geomWkt);

                RegistroAprovadoSilver apr = orquestradorValidacao.transformarRegistro(fonteDadoId, reg);
                if (apr != null) {
                    lista.add(apr);
                }
            }
        } catch (Exception e) {
            log.debug("Nenhum registro órfão de bronze na tabela {}.{}: {}", quarentenaSchema, tabEspelho, e.getMessage());
        }
        return lista;
    }

    private void verificarEAtualizarStatusLoteAposResolucao(Long loteId) {
        if (loteId == null) return;
        String[] tabelas = {
            "q_imovel_rural", "q_area_protegida", "q_auto_infracao",
            "q_embargo_ambiental", "q_desmatamento", "q_foco_calor", "q_municipio"
        };
        long pendentesTotal = 0;
        for (String tab : tabelas) {
            try {
                String sql = "SELECT COUNT(1) FROM " + quarentenaSchema + "." + tab + " q " +
                             "JOIN " + quarentenaSchema + ".status_analise_quarentena sa ON sa.id = q.status_analise_id " +
                             "WHERE q.lote_ingestao_id = ? AND sa.codigo IN ('PENDENTE', 'EM_ANALISE')";
                Long count = jdbcTemplate.queryForObject(sql, Long.class, loteId);
                if (count != null) {
                    pendentesTotal += count;
                }
            } catch (Exception ignored) {}
        }

        if (pendentesTotal == 0) {
            LoteIngestao lote = loteIngestaoRepository.findById(loteId).orElse(null);
            if (lote != null && STATUS_RETIDO_QUARENTENA.equalsIgnoreCase(lote.getStatus())) {
                lote.setStatus(STATUS_PROMOVIDO_SILVER);
                loteIngestaoRepository.save(lote);
                log.info("Lote {} foi completamente resolvido na quarentena e atualizado para {}.", loteId, STATUS_PROMOVIDO_SILVER);
            }
        }
    }

    private String normalizarEntidadeSilver(String ent) {
        if (ent == null) return "imovel_rural";
        String s = ent.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("q_")) s = s.substring(2);
        return s;
    }

    // -------------------------------------------------------------------------
    // Helpers de Mapeamento e Chave Natural
    // -------------------------------------------------------------------------

    public String resolverEntidadeSilver(FonteDado fonte) {
        if (fonte != null && fonte.getEntidadeSilver() != null && !fonte.getEntidadeSilver().isBlank()) {
            return fonte.getEntidadeSilver().trim().toLowerCase(Locale.ROOT);
        }
        if (fonte == null || fonte.getNome() == null) {
            return "imovel_rural";
        }
        String nome = fonte.getNome().toLowerCase(Locale.ROOT);
        if (nome.contains("car") || nome.contains("imóvel") || nome.contains("imovel")) return "imovel_rural";
        if (nome.contains("ibge") || nome.contains("município") || nome.contains("municipio")) return "municipio";
        if (nome.contains("embargo")) return "embargo_ambiental";
        if (nome.contains("auto") || nome.contains("infração") || nome.contains("infracao")) return "auto_infracao";
        if (nome.contains("foco") || nome.contains("queimada")) return "foco_calor";
        if (nome.contains("desmatamento") || nome.contains("prodes")) return "desmatamento";
        if (nome.contains("indígena") || nome.contains("indigena") || nome.contains("conservação") || nome.contains("uc")) return "area_protegida";
        return "imovel_rural";
    }

    public String resolverChaveNatural(String entidadeSilver, FonteDado fonte) {
        String ent = (entidadeSilver != null) ? entidadeSilver.toLowerCase(Locale.ROOT) : "";
        return switch (ent) {
            case "imovel_rural" -> "cod_imovel";
            case "municipio" -> "cod_ibge";
            case "auto_infracao" -> "numero_auto";
            case "embargo_ambiental" -> "numero_termo";
            case "area_protegida" -> "codigo_origem";
            case "desmatamento", "foco_calor" -> "identificador_origem";
            default -> "id";
        };
    }

    public record ResultadoTransicao(
            Long loteId,
            String statusFinal,
            int aprovadosSilver,
            int rejeitadosQuarentena,
            int descartadosDuplicatas,
            String mensagem
    ) {}
}
