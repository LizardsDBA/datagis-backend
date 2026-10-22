package br.gov.sp.cps.datagis.validacao;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import br.gov.sp.cps.datagis.bronze.RegistroBrutoRepository;
import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.FonteDadoRepository;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import br.gov.sp.cps.datagis.controle.LoteIngestaoRepository;
import br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest;
import br.gov.sp.cps.datagis.quarentena.ResultadoRevalidacaoQuarentena;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serviço central de Transição Automática Bronze -> Silver / Quarentena.
 *
 * <p>
 * Aplica a <b>Regra Atômica de Integridade do Lote (All-or-Nothing)</b>:
 * <ul>
 * <li><b>Lote 100% Válido (rejeitados = 0):</b> todos os registros aprovados
 * são promovidos à Silver
 * e o lote é marcado como {@code PROMOVIDO_SILVER};</li>
 * <li><b>Lote com Qualquer Inconsistência (rejeitados &ge; 1):</b> bloqueio
 * total da Silver (0 registros
 * promovidos), os registros rejeitados são enviados à Quarentena com status
 * {@code PENDENTE} e o
 * lote é marcado como {@code RETIDO_QUARENTENA}, pausando o pipeline até
 * decisão do analista;</li>
 * <li><b>Liberação pelo Analista:</b> o lote retido é liberado e seus registros
 * promovidos para a Silver;</li>
 * <li><b>Descarte pelo Analista:</b> o lote retido é marcado como
 * {@code DESCARTADO} e nada sobe para a Silver.</li>
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
    private final String bronzeSchema;
    private final ProgressoTransicaoRegistry progressoTransicaoRegistry;

    @Autowired
    public TransicaoBronzeSilverService(
            LoteIngestaoRepository loteIngestaoRepository,
            FonteDadoRepository fonteDadoRepository,
            RegistroBrutoRepository registroBrutoRepository,
            OrquestradorValidacao orquestradorValidacao,
            PersistidorSilverService persistidorSilverService,
            PersistidorQuarentenaService persistidorQuarentenaService,
            JdbcTemplate jdbcTemplate,
            @Value("${spring.flyway.placeholders.quarentenaSchema:QUARENTENA}") String quarentenaSchema,
            @Value("${spring.flyway.placeholders.bronzeSchema:BRONZE}") String bronzeSchema,
            @Autowired(required = false) ProgressoTransicaoRegistry progressoTransicaoRegistry) {
        this.loteIngestaoRepository = loteIngestaoRepository;
        this.fonteDadoRepository = fonteDadoRepository;
        this.registroBrutoRepository = registroBrutoRepository;
        this.orquestradorValidacao = orquestradorValidacao;
        this.persistidorSilverService = persistidorSilverService;
        this.persistidorQuarentenaService = persistidorQuarentenaService;
        this.jdbcTemplate = jdbcTemplate;
        this.quarentenaSchema = (quarentenaSchema != null && !quarentenaSchema.isBlank()) ? quarentenaSchema.trim()
                : "QUARENTENA";
        this.bronzeSchema = (bronzeSchema != null && !bronzeSchema.isBlank()) ? bronzeSchema.trim() : "BRONZE";
        this.progressoTransicaoRegistry = progressoTransicaoRegistry;
    }

    public TransicaoBronzeSilverService(
            LoteIngestaoRepository loteIngestaoRepository,
            FonteDadoRepository fonteDadoRepository,
            RegistroBrutoRepository registroBrutoRepository,
            OrquestradorValidacao orquestradorValidacao,
            PersistidorSilverService persistidorSilverService,
            PersistidorQuarentenaService persistidorQuarentenaService,
            JdbcTemplate jdbcTemplate,
            String quarentenaSchema,
            String bronzeSchema) {
        this(loteIngestaoRepository, fonteDadoRepository, registroBrutoRepository,
                orquestradorValidacao, persistidorSilverService, persistidorQuarentenaService,
                jdbcTemplate, quarentenaSchema, bronzeSchema, null);
    }

    public TransicaoBronzeSilverService(
            LoteIngestaoRepository loteIngestaoRepository,
            FonteDadoRepository fonteDadoRepository,
            RegistroBrutoRepository registroBrutoRepository,
            OrquestradorValidacao orquestradorValidacao,
            PersistidorSilverService persistidorSilverService,
            PersistidorQuarentenaService persistidorQuarentenaService,
            JdbcTemplate jdbcTemplate,
            String quarentenaSchema) {
        this(loteIngestaoRepository, fonteDadoRepository, registroBrutoRepository,
                orquestradorValidacao, persistidorSilverService, persistidorQuarentenaService,
                jdbcTemplate, quarentenaSchema, "BRONZE", null);
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
        if ("SEM_MUDANCA".equalsIgnoreCase(lote.getStatus())
                || (lote.getQuantidadeRegistros() != null && lote.getQuantidadeRegistros() == 0)) {
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

        if (progressoTransicaoRegistry != null) {
            progressoTransicaoRegistry.iniciar(loteId, registros.size(), "REVALIDANDO_REGRAS");
        }

        // Executa o motor de validações e deduplicação
        ResultadoOrquestracao resOrquestracao = orquestradorValidacao.orquestrarLote(
                fonte != null ? fonte.getId() : null,
                registros,
                campoChaveNatural,
                null);

        // REGRA ATÔMICA ALL-OR-NOTHING:
        // Se houver qualquer registro reprovado (>= 1), o lote inteiro é retido e ZERO
        // registros vão para a Silver.
        int qtdForaDoRecorte = resOrquestracao.totalDescartadosForaDoRecorte();
        String sufixoRecorte = qtdForaDoRecorte > 0
                ? " (" + qtdForaDoRecorte + " fora do recorte territorial PR, descartado(s) sem quarentena)"
                : "";

        if (resOrquestracao.temErros()) {
            int qtdErros = resOrquestracao.rejeitados().size();
            log.warn(
                    "LOTE {} BLOQUEADO: {} inconsistência(s) detectada(s){}. Regra All-or-Nothing aplicada: 0 registros promovidos para a Silver.",
                    loteId, qtdErros, sufixoRecorte);

            // Persiste inconsistências na Quarentena
            persistidorQuarentenaService.persistirRejeitados(entidadeSilver, resOrquestracao.rejeitados());

            // Atualiza status do lote
            lote.setStatus(STATUS_RETIDO_QUARENTENA);
            loteIngestaoRepository.save(lote);

            if (progressoTransicaoRegistry != null) {
                progressoTransicaoRegistry.finalizar(loteId, "RETIDO_QUARENTENA", registros.size(), registros.size(),
                        qtdErros, STATUS_RETIDO_QUARENTENA,
                        "Lote retido na Quarentena com " + qtdErros + " inconsistência(s). 0 registros enviados à Silver." + sufixoRecorte);
            }

            return new ResultadoTransicao(
                    loteId,
                    STATUS_RETIDO_QUARENTENA,
                    0,
                    qtdErros,
                    resOrquestracao.descartadosDedup().size(),
                    "Lote retido na Quarentena com " + qtdErros + " inconsistência(s). 0 registros enviados à Silver." + sufixoRecorte);
        }

        // Lote 100% íntegro: promove todos os registros aprovados diretamente para a
        // Silver
        int qtdAprovados = resOrquestracao.aprovados().size();
        log.info("LOTE {} APROVADO: 100% íntegro. Promovendo {} registro(s) para a Silver ({}.{}){}.",
                loteId, qtdAprovados, "SILVER", entidadeSilver, sufixoRecorte);

        persistidorSilverService.persistirAprovados(entidadeSilver, resOrquestracao.aprovados());

        lote.setStatus(STATUS_PROMOVIDO_SILVER);
        loteIngestaoRepository.save(lote);

        if (progressoTransicaoRegistry != null) {
            progressoTransicaoRegistry.finalizar(loteId, "CONCLUIDO", qtdAprovados, registros.size(),
                    0, STATUS_PROMOVIDO_SILVER,
                    "LOTE APROVADO: 100% íntegro. " + qtdAprovados + " registro(s) promovidos para a Silver." + sufixoRecorte);
        }

        return new ResultadoTransicao(
                loteId,
                STATUS_PROMOVIDO_SILVER,
                qtdAprovados,
                0,
                resOrquestracao.descartadosDedup().size(),
                "Lote integralmente aprovado e promovido à Silver." + sufixoRecorte);
    }

    /**
     * Liberação de um lote retido em quarentena após análise/correção pelo analista.
     * Revalida todos os registros do lote novamente através do motor de validação.
     * Se houver qualquer inconsistência restante, os registros com erro são mantidos
     * na Quarentena e o lote NÃO é promovido para a Silver.
     * Somente se 100% dos registros forem válidos (ou descartados), o lote é promovido à Silver.
     */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
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

        // 1. Sincroniza correções manuais de payload da Quarentena de volta para o
        // Bronze se houver
        sincronizarCorrecoesQuarentenaParaBronze(loteId, entidadeSilver);

        // 2. Busca registros brutos associados ao lote
        List<RegistroBruto> registros = registroBrutoRepository.findByLoteIngestaoIdOrderByIdAsc(loteId);

        // Exclui registros que foram descartados pelo analista na quarentena
        Set<Long> idsDescartados = buscarIdsDescartadosQuarentena(loteId);
        if (!idsDescartados.isEmpty()) {
            registros = registros.stream()
                    .filter(r -> !idsDescartados.contains(r.getId()))
                    .toList();
        }

        // Aplica em memória as correções de geometria registradas na Quarentena
        // (mantendo a Bronze bruta original)
        aplicarGeometriasCorrigidasQuarentena(loteId, entidadeSilver, registros);

        if (registros.isEmpty()) {
            log.warn("Lote {} sem registros brutos a processar ou todos foram descartados.", loteId);
            throw new IllegalArgumentException("O lote #" + loteId + " não possui registros a serem promovidos.");
        }

        if (progressoTransicaoRegistry != null) {
            progressoTransicaoRegistry.iniciar(loteId, registros.size(), "REVALIDANDO_REGRAS");
        }

        // Executa esteira de revalidação e deduplicação de todos os registros do lote
        ResultadoOrquestracao res = orquestradorValidacao.orquestrarLote(
                fonteDadoId,
                registros,
                campoChaveNatural);

        // Se houver qualquer registro reprovado na revalidação, NÃO libera o lote
        if (res.temErros()) {
            int qtdErros = res.rejeitados().size();
            log.warn(
                    "Lote {} revalidado: {} registro(s) ainda contêm erro(s). Liberação para a Silver bloqueada. Registros com erro mantidos na Quarentena.",
                    loteId, qtdErros);

            // Mantém os que têm erro em quarentena (garante status PENDENTE e atualiza erro atual)
            sincronizarRejeitadosRevalidacaoQuarentena(loteId, entidadeSilver, res.rejeitados());

            // Atualiza registros que passaram na revalidação para CORRIGIDO_REPROCESSADO
            sincronizarAprovadosRevalidacaoQuarentena(loteId, entidadeSilver, res.aprovados(), analistaId);

            // Mantém lote com status RETIDO_QUARENTENA
            lote.setStatus(STATUS_RETIDO_QUARENTENA);
            loteIngestaoRepository.save(lote);

            if (progressoTransicaoRegistry != null) {
                progressoTransicaoRegistry.finalizar(loteId, "RETIDO_QUARENTENA", registros.size(), registros.size(),
                        qtdErros, STATUS_RETIDO_QUARENTENA,
                        "O lote #" + loteId + " não pode ser liberado para a Silver: " + qtdErros + " registro(s) ainda contêm erro(s).");
            }

            throw new IllegalArgumentException(
                    "O lote #" + loteId + " não pode ser liberado para a Silver: " + qtdErros
                            + " registro(s) ainda contêm erro(s) após a revalidação. "
                            + "Os registros com inconsistência foram mantidos na Quarentena.");
        }

        // Lote 100% aprovado! Promove todos os registros reunidos para a Silver
        List<RegistroAprovadoSilver> todosParaPersistir = new ArrayList<>(res.aprovados());

        // Inclui eventuais registros da Quarentena sem registro_bruto_id (ex.: seeds)
        List<RegistroAprovadoSilver> itensQuarentenaSemBronze = extrairItensQuarentenaSemBronze(loteId, entidadeSilver,
                fonteDadoId);
        todosParaPersistir.addAll(itensQuarentenaSemBronze);

        persistidorSilverService.persistirAprovados(entidadeSilver, todosParaPersistir);

        // Atualiza registros da quarentena para CORRIGIDO_REPROCESSADO (status_analise_id = 3)
        atualizarStatusQuarentenaPorLote(loteId, "CORRIGIDO_REPROCESSADO", analistaId, null);

        // Atualiza status do lote para PROMOVIDO_SILVER
        lote.setStatus(STATUS_PROMOVIDO_SILVER);
        loteIngestaoRepository.save(lote);

        if (progressoTransicaoRegistry != null) {
            progressoTransicaoRegistry.finalizar(loteId, "CONCLUIDO", todosParaPersistir.size(), registros.size(),
                    0, STATUS_PROMOVIDO_SILVER,
                    "Lote #" + loteId + " revalidado e liberado com sucesso. " + todosParaPersistir.size() + " registros promovidos à Silver.");
        }

        log.info("Lote {} revalidado e liberado com sucesso pelo analista {}. {} registros promovidos à Silver.",
                loteId, analistaId, todosParaPersistir.size());
        return true;
    }

    /**
     * Executa a transição do lote de forma assíncrona desacoplada da thread HTTP.
     */
    @org.springframework.scheduling.annotation.Async("datagisTaskExecutor")
    public void processarLoteAssincrono(Long loteId) {
        try {
            processarLote(loteId);
        } catch (Exception e) {
            log.error("Erro no processamento assíncrono do lote {}: {}", loteId, e.getMessage(), e);
            if (progressoTransicaoRegistry != null) {
                progressoTransicaoRegistry.finalizar(loteId, "ERRO", 0, 0, 0, "FALHA", e.getMessage());
            }
        }
    }

    /**
     * Executa a liberação do lote de forma assíncrona desacoplada da thread HTTP.
     */
    @org.springframework.scheduling.annotation.Async("datagisTaskExecutor")
    public void liberarLoteParaSilverAssincrono(Long loteId, Long analistaId) {
        try {
            liberarLoteParaSilver(loteId, analistaId);
        } catch (IllegalArgumentException e) {
            log.warn("Liberação assíncrona do lote {} barrada por validação: {}", loteId, e.getMessage());
        } catch (Exception e) {
            log.error("Erro fatal na liberação assíncrona do lote {}: {}", loteId, e.getMessage(), e);
            if (progressoTransicaoRegistry != null) {
                progressoTransicaoRegistry.finalizar(loteId, "ERRO", 0, 0, 0, "FALHA", e.getMessage());
            }
        }
    }

    /**
     * Revalida e aprova um registro individual retido na Quarentena.
     * <p>
     * Se o registro possuir pendência de {@code VALOR_FORA_DO_DOMINIO} e for
     * validado com sucesso,
     * uma tentativa coletiva de revalidação e promoção em cascata é disparada para
     * todos os outros
     * registros da quarentena retidos pelo mesmo tipo de erro de domínio.
     * <p>
     * Caso o registro inspecionado NÃO passe na validação, uma
     * {@link IllegalArgumentException} é lançada
     * e NENHUM outro registro é alterado.
     * <p>
     * <b>Regra Atômica por Lote:</b> Os registros corrigidos NÃO são persistidos
     * individualmente
     * na camada Silver. Permanecem no Bronze e Quarentena (status
     * {@code CORRIGIDO_REPROCESSADO})
     * e aguardam a liberação conjunta de seus respectivos lotes.
     */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public ResultadoRevalidacaoQuarentena promoverRegistroQuarentenaParaSilver(String entidade, Long quarentenaId,
            AcaoQuarentenaRequest request) {
        if (quarentenaId == null) {
            return null;
        }

        String entSilver = normalizarEntidadeSilver(entidade);
        String tabEspelho = "q_" + entSilver;

        // 1. Carrega o registro da tabela espelho de quarentena com o código do erro
        String sql = "SELECT q.id, q.fonte_dado_id, q.lote_ingestao_id, q.registro_bruto_id, " +
                "q.geometria_wkt, JSON_SERIALIZE(q.payload_original RETURNING CLOB) AS payload_original, " +
                "q.campo_problema, " +
                "te.codigo AS tipo_erro_codigo " +
                "FROM " + quarentenaSchema + "." + tabEspelho + " q " +
                "LEFT JOIN " + quarentenaSchema + ".tipo_erro_quarentena te ON te.id = q.tipo_erro_id " +
                "WHERE q.id = ?";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, quarentenaId);
        if (rows.isEmpty()) {
            log.warn("Registro de quarentena {} não encontrado na tabela {}.{}", quarentenaId, quarentenaSchema,
                    tabEspelho);
            return null;
        }

        Map<String, Object> row = rows.get(0);
        Long fonteDadoId = row.get("fonte_dado_id") != null ? ((Number) row.get("fonte_dado_id")).longValue() : null;
        Long loteIngestaoId = row.get("lote_ingestao_id") != null ? ((Number) row.get("lote_ingestao_id")).longValue()
                : null;
        Long regBrutoId = row.get("registro_bruto_id") != null ? ((Number) row.get("registro_bruto_id")).longValue()
                : null;
        String geomWkt = row.get("geometria_wkt") != null ? row.get("geometria_wkt").toString() : null;
        String payloadOriginal = row.get("payload_original") != null ? row.get("payload_original").toString() : null;
        String campoProblemaOriginal = row.get("campo_problema") != null ? row.get("campo_problema").toString() : null;
        String tipoErroCodigo = row.get("tipo_erro_codigo") != null ? row.get("tipo_erro_codigo").toString() : null;

        // 2. Determina o payload e a geometria efetivos (se o operador editou no modal,
        // usa o corrigido)
        String payloadEfetivo = (request != null && request.payloadCorrigido() != null
                && !request.payloadCorrigido().trim().isBlank())
                        ? request.payloadCorrigido().trim()
                        : payloadOriginal;
        String geomWktEfetivo = (request != null && request.geometriaCorrigida() != null
                && !request.geometriaCorrigida().trim().isBlank())
                        ? request.geometriaCorrigida().trim()
                        : geomWkt;

        // 3. Monta o RegistroBruto em memória para validação (sem alterar banco ainda)
        RegistroBruto reg = null;
        if (regBrutoId != null && regBrutoId > 0) {
            reg = registroBrutoRepository.findById(regBrutoId).orElse(null);
        }
        if (reg == null) {
            reg = RegistroBruto.builder()
                    .id(regBrutoId)
                    .fonteDadoId(fonteDadoId != null ? fonteDadoId : 1L)
                    .loteIngestaoId(loteIngestaoId != null ? loteIngestaoId : 1L)
                    .payload(payloadEfetivo)
                    .geometriaWkt(geomWktEfetivo)
                    .build();
        } else {
            // Cria cópia desanexada para validação, evitando dirty-checking acidental da
            // sessão Hibernate
            reg = RegistroBruto.builder()
                    .id(reg.getId())
                    .fonteDadoId(reg.getFonteDadoId())
                    .loteIngestaoId(reg.getLoteIngestaoId())
                    .payload(payloadEfetivo)
                    .geometriaWkt((geomWktEfetivo != null && !geomWktEfetivo.isBlank()) ? geomWktEfetivo
                            : reg.getGeometriaWkt())
                    .hashRegistro(reg.getHashRegistro())
                    .dataIngestao(reg.getDataIngestao())
                    .build();
        }

        Long analistaId = request != null ? request.analistaId() : null;

        // 4. Valida o registro individualmente contra a esteira de regras
        if (orquestradorValidacao != null) {
            OrquestradorValidacao.ResultadoValidacaoRegistro resVal = orquestradorValidacao
                    .validarRegistroIndividual(fonteDadoId, reg);
            if (resVal != null && !resVal.valido()) {
                String motivo = resVal.rejeitado() != null ? resVal.rejeitado().motivoRejeicao()
                        : "Inconsistência na revalidação";
                String campo = resVal.rejeitado() != null ? resVal.rejeitado().campoProblema() : "campo";
                String novoTipoErro = resVal.rejeitado() != null ? resVal.rejeitado().tipoErro() : null;
                String valorRecebido = resVal.rejeitado() != null ? resVal.rejeitado().valorRecebido() : null;

                // Sincroniza o registro de quarentena com o erro ATUAL encontrado na
                // revalidação.
                // Sem isso, o registro fica com o dado corrigido no banco mas exibindo o erro
                // antigo,
                // travado em um estado desalinhado (bug reportado pela equipe).
                if (novoTipoErro != null) {
                    try {
                        jdbcTemplate.update(
                                "UPDATE " + quarentenaSchema + "." + tabEspelho + " SET " +
                                        "tipo_erro_id = (SELECT id FROM " + quarentenaSchema
                                        + ".tipo_erro_quarentena WHERE codigo = ?), " +
                                        "campo_problema = ?, valor_recebido = ?, motivo_rejeicao = ?, " +
                                        "status_analise_id = (SELECT id FROM " + quarentenaSchema
                                        + ".status_analise_quarentena WHERE codigo = 'PENDENTE') " +
                                        "WHERE id = ?",
                                novoTipoErro, campo, valorRecebido, motivo, quarentenaId);
                    } catch (Exception exUpd) {
                        log.warn("Falha ao sincronizar novo erro de revalidação na quarentena {}: {}", quarentenaId,
                                exUpd.getMessage());
                    }
                }

                // Preserva payload_original / geometria_wkt editados na quarentena se foram informados
                if (request != null && request.payloadCorrigido() != null && !request.payloadCorrigido().trim().isBlank()) {
                    try {
                        jdbcTemplate.update(
                                "UPDATE " + quarentenaSchema + "." + tabEspelho + " SET payload_original = ? WHERE id = ?",
                                payloadEfetivo, quarentenaId);
                    } catch (Exception e) {
                        log.debug("Aviso ao atualizar payload na quarentena {}.{}: {}", quarentenaSchema, tabEspelho,
                                e.getMessage());
                    }
                }
                if (request != null && request.geometriaCorrigida() != null && !request.geometriaCorrigida().trim().isBlank()) {
                    try {
                        jdbcTemplate.update(
                                "UPDATE " + quarentenaSchema + "." + tabEspelho + " SET geometria_wkt = ? WHERE id = ?",
                                geomWktEfetivo, quarentenaId);
                    } catch (Exception e) {
                        log.debug("Aviso ao atualizar geometria_wkt na quarentena {}.{}: {}", quarentenaSchema, tabEspelho,
                                e.getMessage());
                    }
                }

                // REAVALIAÇÃO EM CASCATA NA TRANSIÇÃO DE ERRO:
                // Se o erro original era VALOR_FORA_DO_DOMINIO e o registro passou da validação de domínio
                // (transitando para outro tipo de erro OU para outro campo), repassa a revalidação para
                // todos os outros registros com pendência de domínio ANTES de disparar o próximo erro.
                boolean eraErroDominio = "VALOR_FORA_DO_DOMINIO".equalsIgnoreCase(tipoErroCodigo);
                boolean transitouParaOutroErro = novoTipoErro != null && (
                        !"VALOR_FORA_DO_DOMINIO".equalsIgnoreCase(novoTipoErro)
                        || (campoProblemaOriginal != null && !campoProblemaOriginal.equalsIgnoreCase(campo))
                );

                if (eraErroDominio && transitouParaOutroErro) {
                    Set<Long> lotesAfetadosColetivo = new LinkedHashSet<>();
                    int[] resultadoColetivo = reavaliarOutrosRegistrosQuarentenaPorDominio(quarentenaId, analistaId,
                            lotesAfetadosColetivo);
                    for (Long loteId : lotesAfetadosColetivo) {
                        verificarEAtualizarStatusLoteAposResolucao(loteId, analistaId);
                    }
                    log.info(
                            "Registro {} transitou de VALOR_FORA_DO_DOMINIO ({}) para {} ({}). Reavaliação em cascata executada: {} aprovado(s), {} mantido(s).",
                            quarentenaId, campoProblemaOriginal, novoTipoErro, campo, resultadoColetivo[0], resultadoColetivo[1]);
                }

                log.warn("Tentativa de revalidação falhou para quarentena {}. Campo '{}': {}", quarentenaId, campo,
                        motivo);
                throw new IllegalArgumentException("Registro ainda contém erro no campo '" + campo + "': " + motivo);
            }
        }

        // 5. REGISTRO APROVADO! Agora sim aplicamos as alterações de banco para o item
        // inspecionado
        // Se corrigido, atualiza a quarentena com o novo payload
        if (request != null && request.payloadCorrigido() != null && !request.payloadCorrigido().trim().isBlank()) {
            try {
                jdbcTemplate.update(
                        "UPDATE " + quarentenaSchema + "." + tabEspelho + " SET payload_original = ? WHERE id = ?",
                        payloadEfetivo, quarentenaId);
            } catch (Exception e) {
                log.debug("Aviso ao atualizar payload na quarentena {}.{}: {}", quarentenaSchema, tabEspelho,
                        e.getMessage());
            }
        }

        // Se corrigida a geometria, atualiza a quarentena com o novo WKT
        // (BRONZE.registro_bruto preserva a geometria original)
        if (request != null && request.geometriaCorrigida() != null && !request.geometriaCorrigida().trim().isBlank()) {
            try {
                jdbcTemplate.update(
                        "UPDATE " + quarentenaSchema + "." + tabEspelho + " SET geometria_wkt = ? WHERE id = ?",
                        geomWktEfetivo, quarentenaId);
            } catch (Exception e) {
                log.debug("Aviso ao atualizar geometria_wkt na quarentena {}.{}: {}", quarentenaSchema, tabEspelho,
                        e.getMessage());
            }
        }

        // Atualiza o payload no RegistroBruto via JDBC caso o payload tenha sido
        // corrigido
        if (request != null && request.payloadCorrigido() != null && !request.payloadCorrigido().trim().isBlank()
                && regBrutoId != null && regBrutoId > 0) {
            try {
                jdbcTemplate.update(
                        "UPDATE " + bronzeSchema + ".registro_bruto SET payload = ? WHERE id = ?",
                        payloadEfetivo, regBrutoId);
            } catch (Exception e) {
                log.warn("Falha ao atualizar payload no registro_bruto {}: {}", regBrutoId, e.getMessage());
            }
        }

        // Atualiza o status do registro na Quarentena para CORRIGIDO_REPROCESSADO
        String sqlUpdateStatus = "UPDATE " + quarentenaSchema + "." + tabEspelho +
                " SET status_analise_id = (SELECT id FROM " + quarentenaSchema
                + ".status_analise_quarentena WHERE codigo = 'CORRIGIDO_REPROCESSADO'), " +
                " analista_responsavel_id = ?, data_resolucao = SYSTIMESTAMP " +
                " WHERE id = ?";
        jdbcTemplate.update(sqlUpdateStatus, analistaId, quarentenaId);

        log.info("Registro de quarentena {} ({}) revalidado com sucesso.", quarentenaId, tabEspelho);

        Set<Long> lotesAfetados = new LinkedHashSet<>();
        if (loteIngestaoId != null) {
            lotesAfetados.add(loteIngestaoId);
        }

        // 6. REAVALIAÇÃO EM CASCATA: Se o erro original era VALOR_FORA_DO_DOMINIO,
        // tenta revalidar os outros
        int totalColetivoAprovados = 0;
        int totalColetivoMantidos = 0;
        if ("VALOR_FORA_DO_DOMINIO".equalsIgnoreCase(tipoErroCodigo)) {
            int[] resultadoColetivo = reavaliarOutrosRegistrosQuarentenaPorDominio(quarentenaId, analistaId,
                    lotesAfetados);
            totalColetivoAprovados = resultadoColetivo[0];
            totalColetivoMantidos = resultadoColetivo[1];
        }

        // 7. REGRA ALL-OR-NOTHING: Para cada lote afetado, verifica se todas as
        // pendências foram concluídas
        List<Long> lotesLiberados = new ArrayList<>();
        for (Long loteId : lotesAfetados) {
            boolean liberado = verificarEAtualizarStatusLoteAposResolucao(loteId, analistaId);
            if (liberado) {
                lotesLiberados.add(loteId);
            }
        }

        return ResultadoRevalidacaoQuarentena.coletivo(quarentenaId, totalColetivoAprovados, totalColetivoMantidos,
                lotesLiberados);
    }

    /**
     * Reavalia todos os outros registros retidos na Quarentena com o erro
     * VALOR_FORA_DO_DOMINIO.
     * Registros que passarem na validação são promovidos a CORRIGIDO_REPROCESSADO.
     * Registros que ainda falharem permanecem retidos silenciosamente na
     * Quarentena.
     *
     * @param quarentenaIdIgnorar ID do registro original que já foi processado
     * @param analistaId          ID do analista responsável
     * @param lotesAfetados       conjunto onde serão adicionados os IDs dos lotes
     *                            com registros aprovados
     * @return array com [totalAprovados, totalMantidos]
     */
    private int[] reavaliarOutrosRegistrosQuarentenaPorDominio(Long quarentenaIdIgnorar, Long analistaId,
            Set<Long> lotesAfetados) {
        int aprovados = 0;
        int mantidos = 0;
        String[] tabelas = {
                "q_imovel_rural", "q_area_protegida", "q_auto_infracao",
                "q_embargo_ambiental", "q_desmatamento", "q_foco_calor", "q_municipio"
        };

        for (String tab : tabelas) {
            try {
                String sql = "SELECT q.id, q.fonte_dado_id, q.lote_ingestao_id, q.registro_bruto_id, " +
                        "q.geometria_wkt, JSON_SERIALIZE(q.payload_original RETURNING CLOB) AS payload_original " +
                        "FROM " + quarentenaSchema + "." + tab + " q " +
                        "JOIN " + quarentenaSchema + ".status_analise_quarentena sa ON sa.id = q.status_analise_id " +
                        "JOIN " + quarentenaSchema + ".tipo_erro_quarentena te ON te.id = q.tipo_erro_id " +
                        "WHERE sa.codigo IN ('PENDENTE', 'EM_ANALISE') " +
                        "  AND te.codigo = 'VALOR_FORA_DO_DOMINIO' " +
                        "  AND q.id != ?";

                List<Map<String, Object>> candidatos = jdbcTemplate.queryForList(sql,
                        quarentenaIdIgnorar != null ? quarentenaIdIgnorar : -1L);
                if (candidatos == null || candidatos.isEmpty()) {
                    continue;
                }

                for (Map<String, Object> c : candidatos) {
                    try {
                        Long cId = ((Number) c.get("id")).longValue();
                        Long cFonteDadoId = c.get("fonte_dado_id") != null
                                ? ((Number) c.get("fonte_dado_id")).longValue()
                                : null;
                        Long cLoteId = c.get("lote_ingestao_id") != null
                                ? ((Number) c.get("lote_ingestao_id")).longValue()
                                : null;
                        Long cRegBrutoId = c.get("registro_bruto_id") != null
                                ? ((Number) c.get("registro_bruto_id")).longValue()
                                : null;
                        String cGeomWkt = c.get("geometria_wkt") != null ? c.get("geometria_wkt").toString() : null;
                        String cPayload = c.get("payload_original") != null ? c.get("payload_original").toString()
                                : null;

                        RegistroBruto cReg = RegistroBruto.builder()
                                .id(cRegBrutoId)
                                .fonteDadoId(cFonteDadoId != null ? cFonteDadoId : 1L)
                                .loteIngestaoId(cLoteId != null ? cLoteId : 1L)
                                .payload(cPayload)
                                .geometriaWkt(cGeomWkt)
                                .build();

                        boolean valido = false;
                        if (orquestradorValidacao != null) {
                            var resVal = orquestradorValidacao.validarRegistroIndividual(cFonteDadoId, cReg);
                            valido = (resVal != null && resVal.valido());
                        }

                        if (valido) {
                            String sqlUpd = "UPDATE " + quarentenaSchema + "." + tab +
                                    " SET status_analise_id = (SELECT id FROM " + quarentenaSchema
                                    + ".status_analise_quarentena WHERE codigo = 'CORRIGIDO_REPROCESSADO'), " +
                                    " analista_responsavel_id = ?, data_resolucao = SYSTIMESTAMP " +
                                    " WHERE id = ?";
                            jdbcTemplate.update(sqlUpd, analistaId, cId);

                            if (cLoteId != null) {
                                lotesAfetados.add(cLoteId);
                            }
                            aprovados++;
                        } else {
                            mantidos++;
                        }
                    } catch (Exception exItem) {
                        log.warn("Aviso ao reavaliar registro coletivo em {}: {}", tab, exItem.getMessage());
                        mantidos++;
                    }
                }
            } catch (Exception e) {
                log.warn("Aviso ao consultar candidatos para reavaliação coletiva em {}.{}: {}", quarentenaSchema, tab,
                        e.getMessage());
            }
        }
        return new int[] { aprovados, mantidos };
    }

    /**
     * Descarte total de um lote retido em quarentena pelo analista.
     * Marca o lote e os registros da quarentena como DESCARTADO. Nenhum registro
     * sobe para a Silver.
     */
    @Transactional
    public boolean descartarLote(Long loteId, String motivo, Long analistaId) {
        LoteIngestao lote = loteIngestaoRepository.findById(loteId).orElse(null);
        if (lote == null) {
            return false;
        }

        atualizarStatusQuarentenaPorLote(loteId, "DESCARTADO", analistaId,
                motivo != null ? motivo : "Descartado pelo analista");

        lote.setStatus(STATUS_DESCARTADO);
        loteIngestaoRepository.save(lote);

        log.info("Lote {} foi descartado pelo analista {}. 0 registros na Silver.", loteId, analistaId);
        return true;
    }

    private void atualizarStatusQuarentenaPorLote(Long loteId, String codigoStatus, Long analistaId,
            String motivoExtra) {
        String[] tabelas = {
                "q_imovel_rural", "q_area_protegida", "q_auto_infracao",
                "q_embargo_ambiental", "q_desmatamento", "q_foco_calor", "q_municipio"
        };
        for (String tab : tabelas) {
            try {
                if (motivoExtra != null && !motivoExtra.isBlank()) {
                    String sql = "UPDATE " + quarentenaSchema + "." + tab +
                            " SET status_analise_id = (SELECT id FROM " + quarentenaSchema
                            + ".status_analise_quarentena WHERE codigo = ?), " +
                            " data_resolucao = SYSTIMESTAMP, analista_responsavel_id = ?, " +
                            " motivo_rejeicao = SUBSTR(COALESCE(motivo_rejeicao, '') || ' | Descartado: ' || ?, 1, 500) "
                            +
                            " WHERE lote_ingestao_id = ?";
                    int count = jdbcTemplate.update(sql, codigoStatus, analistaId, motivoExtra, loteId);
                    if (count > 0) {
                        log.info("Atualizados {} registros em {}.{} para status {} no lote {}", count, quarentenaSchema,
                                tab, codigoStatus, loteId);
                    }
                } else {
                    String sql = "UPDATE " + quarentenaSchema + "." + tab +
                            " SET status_analise_id = (SELECT id FROM " + quarentenaSchema
                            + ".status_analise_quarentena WHERE codigo = ?), " +
                            " data_resolucao = SYSTIMESTAMP, analista_responsavel_id = ? " +
                            " WHERE lote_ingestao_id = ?" +
                            ("CORRIGIDO_REPROCESSADO".equalsIgnoreCase(codigoStatus)
                                    ? " AND status_analise_id != (SELECT id FROM " + quarentenaSchema
                                            + ".status_analise_quarentena WHERE codigo = 'DESCARTADO')"
                                    : "");
                    int count = jdbcTemplate.update(sql, codigoStatus, analistaId, loteId);
                    if (count > 0) {
                        log.info("Atualizados {} registros em {}.{} para status {} no lote {}", count, quarentenaSchema,
                                tab, codigoStatus, loteId);
                    }
                }
            } catch (Exception e) {
                log.debug("Tabela {}.{} não afetada pelo lote {}: {}", quarentenaSchema, tab, loteId, e.getMessage());
            }
        }
    }

    private void sincronizarRejeitadosRevalidacaoQuarentena(Long loteId, String entidadeSilver,
            List<RegistroRejeitadoQuarentena> rejeitados) {
        if (loteId == null || rejeitados == null || rejeitados.isEmpty()) {
            return;
        }
        String tabEspelho = "q_" + normalizarEntidadeSilver(entidadeSilver);

        for (RegistroRejeitadoQuarentena rej : rejeitados) {
            if (rej.registroBruto() == null || rej.registroBruto().getId() == null) {
                continue;
            }
            Long regId = rej.registroBruto().getId();
            try {
                String sql = "UPDATE " + quarentenaSchema + "." + tabEspelho + " SET " +
                        "status_analise_id = (SELECT id FROM " + quarentenaSchema
                        + ".status_analise_quarentena WHERE codigo = 'PENDENTE'), " +
                        "tipo_erro_id = (SELECT id FROM " + quarentenaSchema
                        + ".tipo_erro_quarentena WHERE codigo = ?), " +
                        "campo_problema = ?, valor_recebido = ?, motivo_rejeicao = ?, " +
                        "data_resolucao = NULL " +
                        "WHERE lote_ingestao_id = ? AND registro_bruto_id = ?";
                int count = jdbcTemplate.update(sql,
                        rej.tipoErro(), rej.campoProblema(), rej.valorRecebido(), rej.motivoRejeicao(),
                        loteId, regId);
                if (count == 0) {
                    persistidorQuarentenaService.persistirRejeitados(entidadeSilver, List.of(rej));
                }
            } catch (Exception e) {
                log.debug("Aviso ao sincronizar rejeição na quarentena {}.{}: {}", quarentenaSchema, tabEspelho,
                        e.getMessage());
            }
        }
    }

    private void sincronizarAprovadosRevalidacaoQuarentena(Long loteId, String entidadeSilver,
            List<RegistroAprovadoSilver> aprovados, Long analistaId) {
        if (loteId == null || aprovados == null || aprovados.isEmpty()) {
            return;
        }
        String tabEspelho = "q_" + normalizarEntidadeSilver(entidadeSilver);

        for (RegistroAprovadoSilver apr : aprovados) {
            if (apr.registroBruto() == null || apr.registroBruto().getId() == null) {
                continue;
            }
            Long regId = apr.registroBruto().getId();
            try {
                String sql = "UPDATE " + quarentenaSchema + "." + tabEspelho + " SET " +
                        "status_analise_id = (SELECT id FROM " + quarentenaSchema
                        + ".status_analise_quarentena WHERE codigo = 'CORRIGIDO_REPROCESSADO'), " +
                        "analista_responsavel_id = ?, data_resolucao = SYSTIMESTAMP " +
                        "WHERE lote_ingestao_id = ? AND registro_bruto_id = ? " +
                        "AND status_analise_id != (SELECT id FROM " + quarentenaSchema
                        + ".status_analise_quarentena WHERE codigo = 'DESCARTADO')";
                jdbcTemplate.update(sql, analistaId, loteId, regId);
            } catch (Exception e) {
                log.debug("Aviso ao sincronizar aprovação na quarentena {}.{}: {}", quarentenaSchema, tabEspelho,
                        e.getMessage());
            }
        }
    }

    private Set<Long> buscarIdsDescartadosQuarentena(Long loteId) {
        Set<Long> descartados = new HashSet<>();
        if (loteId == null)
            return descartados;
        String[] tabelas = {
                "q_imovel_rural", "q_area_protegida", "q_auto_infracao",
                "q_embargo_ambiental", "q_desmatamento", "q_foco_calor", "q_municipio"
        };
        for (String tab : tabelas) {
            try {
                String sql = "SELECT q.registro_bruto_id FROM " + quarentenaSchema + "." + tab + " q " +
                        "JOIN " + quarentenaSchema + ".status_analise_quarentena sa ON sa.id = q.status_analise_id " +
                        "WHERE q.lote_ingestao_id = ? AND sa.codigo = 'DESCARTADO' AND q.registro_bruto_id IS NOT NULL";
                List<Long> ids = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong(1), loteId);
                descartados.addAll(ids);
            } catch (Exception ignored) {
            }
        }
        return descartados;
    }

    /**
     * Sincroniza payload_original da Quarentena de volta pro Bronze, só pros poucos
     * registros que o analista realmente editou manualmente. Achado ao vivo tentando
     * liberar um lote de 333.604 registros (foco_calor): a versão anterior fazia 1
     * UPDATE por registro via loop Java (333 mil round-trips, cada um reescrevendo o
     * payload inteiro mesmo quando IDÊNTICO ao que já estava no Bronze - a esmagadora
     * maioria nunca foi editada) - sessao Oracle presa 21+ minutos em "Allocate PGA
     * memory from OS", igual ao achado do indice espacial (V049). Trocado por 1 unico
     * MERGE server-side, com DBMS_LOB.COMPARE pulando linhas onde o payload nao mudou -
     * mesmo resultado, sem os round-trips desnecessarios.
     */
    private void sincronizarCorrecoesQuarentenaParaBronze(Long loteId, String entidadeSilver) {
        String tabEspelho = "q_" + normalizarEntidadeSilver(entidadeSilver);
        try {
            String sql = "MERGE INTO " + bronzeSchema + ".registro_bruto br " +
                    "USING (SELECT registro_bruto_id, JSON_SERIALIZE(payload_original RETURNING CLOB) AS payload_novo " +
                    "         FROM " + quarentenaSchema + "." + tabEspelho + " " +
                    "        WHERE lote_ingestao_id = ? AND registro_bruto_id IS NOT NULL " +
                    "          AND payload_original IS NOT NULL) q " +
                    "ON (br.id = q.registro_bruto_id) " +
                    "WHEN MATCHED THEN UPDATE SET br.payload = q.payload_novo " +
                    "WHERE DBMS_LOB.COMPARE(br.payload, q.payload_novo) != 0";
            int count = jdbcTemplate.update(sql, loteId);
            if (count > 0) {
                log.info("Sincronizados {} payload(s) editado(s) manualmente da quarentena {}.{} pro Bronze (lote {})",
                        count, quarentenaSchema, tabEspelho, loteId);
            }
        } catch (Exception e) {
            log.debug("Aviso ao sincronizar correções da quarentena {}.{}: {}", quarentenaSchema, tabEspelho,
                    e.getMessage());
        }
    }

    private void aplicarGeometriasCorrigidasQuarentena(Long loteId, String entidadeSilver,
            List<RegistroBruto> registros) {
        if (loteId == null || registros == null || registros.isEmpty())
            return;
        String tabEspelho = "q_" + normalizarEntidadeSilver(entidadeSilver);
        try {
            String sql = "SELECT registro_bruto_id, geometria_wkt FROM " + quarentenaSchema + "." + tabEspelho +
                    " WHERE lote_ingestao_id = ? AND registro_bruto_id IS NOT NULL AND geometria_wkt IS NOT NULL";
            Map<Long, String> geomPorRegId = new HashMap<>();
            jdbcTemplate.query(sql, (rs, rowNum) -> {
                Long rId = rs.getLong("registro_bruto_id");
                String gWkt = rs.getString("geometria_wkt");
                if (rId != null && gWkt != null && !gWkt.isBlank()) {
                    geomPorRegId.put(rId, gWkt);
                }
                return null;
            }, loteId);

            for (RegistroBruto r : registros) {
                if (r != null && geomPorRegId.containsKey(r.getId())) {
                    r.setGeometriaWkt(geomPorRegId.get(r.getId()));
                }
            }
        } catch (Exception e) {
            log.debug("Aviso ao aplicar geometrias corrigidas da quarentena {}.{}: {}", quarentenaSchema, tabEspelho,
                    e.getMessage());
        }
    }

    private List<RegistroAprovadoSilver> extrairItensQuarentenaSemBronze(Long loteId, String entidadeSilver,
            Long fonteDadoId) {
        String tabEspelho = "q_" + normalizarEntidadeSilver(entidadeSilver);
        List<RegistroAprovadoSilver> lista = new ArrayList<>();
        try {
            String sql = "SELECT q.id, q.fonte_dado_id, q.lote_ingestao_id, q.geometria_wkt, " +
                    "JSON_SERIALIZE(q.payload_original RETURNING CLOB) AS payload_original " +
                    "FROM " + quarentenaSchema + "." + tabEspelho + " q " +
                    "JOIN " + quarentenaSchema + ".status_analise_quarentena sa ON sa.id = q.status_analise_id " +
                    "WHERE q.lote_ingestao_id = ? AND q.registro_bruto_id IS NULL AND sa.codigo != 'DESCARTADO'";
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
            log.debug("Nenhum registro órfão de bronze na tabela {}.{}: {}", quarentenaSchema, tabEspelho,
                    e.getMessage());
        }
        return lista;
    }

    public boolean verificarEAtualizarStatusLoteAposResolucao(Long loteId, Long analistaId) {
        if (loteId == null)
            return false;
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
            } catch (Exception ignored) {
            }
        }

        if (pendentesTotal == 0) {
            LoteIngestao lote = loteIngestaoRepository.findById(loteId).orElse(null);
            if (lote != null && STATUS_RETIDO_QUARENTENA.equalsIgnoreCase(lote.getStatus())) {
                log.info(
                        "Todos os registros do lote {} na quarentena foram resolvidos. Liberando lote completo para a Silver...",
                        loteId);
                try {
                    return liberarLoteParaSilver(loteId, analistaId);
                } catch (IllegalArgumentException e) {
                    log.warn("Tentativa de liberação automática do lote {} bloqueada na revalidação: {}", loteId, e.getMessage());
                    return false;
                }
            }
        } else {
            log.info(
                    "Lote {} ainda possui {} registro(s) pendente(s) na quarentena. Lote permanece retido aguardando resolução total.",
                    loteId, pendentesTotal);
        }
        return false;
    }

    public boolean verificarEAtualizarStatusLoteAposResolucao(Long loteId) {
        return verificarEAtualizarStatusLoteAposResolucao(loteId, null);
    }

    private String normalizarEntidadeSilver(String ent) {
        if (ent == null)
            return "imovel_rural";
        String s = ent.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("q_"))
            s = s.substring(2);
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
        if (nome.contains("car") || nome.contains("imóvel") || nome.contains("imovel"))
            return "imovel_rural";
        if (nome.contains("bioma"))
            return "bioma";
        if (nome.contains("ibge") || nome.contains("município") || nome.contains("municipio"))
            return "municipio";
        if (nome.contains("embargo"))
            return "embargo_ambiental";
        if (nome.contains("auto") || nome.contains("infração") || nome.contains("infracao"))
            return "auto_infracao";
        if (nome.contains("foco") || nome.contains("queimada"))
            return "foco_calor";
        if (nome.contains("desmatamento") || nome.contains("prodes"))
            return "desmatamento";
        if (nome.contains("indígena") || nome.contains("indigena") || nome.contains("conservação")
                || nome.contains("uc"))
            return "area_protegida";
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
            // reserva_legal/app_declarada/vegetacao_nativa_declarada: identificador_origem
            // (objectid do ArcGIS), NAO cod_imovel - um imovel pode ter varios poligonos
            // de RL/APP/vegetacao nao-contiguos, confirmado ao vivo contra o servico real
            // (docs/tutorial-acesso-fontes-pendentes.md §0-bis). cod_imovel como chave
            // natural descartaria fragmentos reais como se fossem duplicata.
            case "reserva_legal", "app_declarada", "vegetacao_nativa_declarada" -> "identificador_origem";
            case "bioma" -> "CD_BIOMA";
            default -> "id";
        };
    }

    public record ResultadoTransicao(
            Long loteId,
            String statusFinal,
            int aprovadosSilver,
            int rejeitadosQuarentena,
            int descartadosDuplicatas,
            String mensagem) {
    }
}
