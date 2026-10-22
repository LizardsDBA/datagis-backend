package br.gov.sp.cps.datagis.quarentena;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import br.gov.sp.cps.datagis.validacao.DominioService;
import br.gov.sp.cps.datagis.validacao.NovoDominioRequest;
import br.gov.sp.cps.datagis.validacao.OrquestradorValidacao;
import br.gov.sp.cps.datagis.validacao.TransicaoBronzeSilverService;
import br.gov.sp.cps.datagis.validacao.ValidadorDominio;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuarentenaService {

    private static final Logger log = LoggerFactory.getLogger(QuarentenaService.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Pattern PATTERN_TABELA_REF =
            Pattern.compile("tabela de referência [\"']?([a-zA-Z0-9_]+)[\"']?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_TIPO_AP =
            Pattern.compile("[\"'](?:tipo_area_protegida|tipo_uc|tipo_area|modalidade)[\"']\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_CAT_AP =
            Pattern.compile("[\"'](?:categoria_area_protegida|categoria|categoria_uc)[\"']\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbcTemplate;
    private final TransicaoBronzeSilverService transicaoService;
    private final DominioService dominioService;

    // Mapeamento das tabelas espelho da Quarentena e suas respectivas chaves naturais
    private static final Map<String, TabelaEspelhoConfig> TABELAS_ESPELHO = new LinkedHashMap<>();

    static {
        TABELAS_ESPELHO.put("imovel_rural", new TabelaEspelhoConfig("q_imovel_rural", "q.cod_imovel"));
        TABELAS_ESPELHO.put("area_protegida", new TabelaEspelhoConfig("q_area_protegida", "q.codigo_origem"));
        TABELAS_ESPELHO.put("embargo_ambiental", new TabelaEspelhoConfig("q_embargo_ambiental", "q.numero_termo"));
        TABELAS_ESPELHO.put("auto_infracao", new TabelaEspelhoConfig("q_auto_infracao", "q.numero_auto"));
        TABELAS_ESPELHO.put("desmatamento", new TabelaEspelhoConfig("q_desmatamento", "CAST(q.id AS VARCHAR2(50))"));
        TABELAS_ESPELHO.put("foco_calor", new TabelaEspelhoConfig("q_foco_calor", "CAST(q.id AS VARCHAR2(50))"));
        TABELAS_ESPELHO.put("municipio", new TabelaEspelhoConfig("q_municipio", "q.cod_ibge"));
        TABELAS_ESPELHO.put("app_declarada", new TabelaEspelhoConfig("q_app_declarada", "q.identificador_origem"));
        TABELAS_ESPELHO.put("reserva_legal", new TabelaEspelhoConfig("q_reserva_legal", "q.identificador_origem"));
        TABELAS_ESPELHO.put("vegetacao_nativa_declarada", new TabelaEspelhoConfig("q_vegetacao_nativa_declarada", "q.identificador_origem"));
    }

    public record TabelaEspelhoConfig(String nomeTabela, String expressaoChave) {}

    @Autowired
    public QuarentenaService(
            JdbcTemplate jdbcTemplate,
            @Autowired(required = false) TransicaoBronzeSilverService transicaoService,
            @Autowired(required = false) DominioService dominioService) {
        this.jdbcTemplate = jdbcTemplate;
        this.transicaoService = transicaoService;
        this.dominioService = dominioService;
    }

    public QuarentenaService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null, null);
    }


    public boolean liberarLote(Long loteId, Long analistaId) {
        if (transicaoService != null) {
            return transicaoService.liberarLoteParaSilver(loteId, analistaId);
        }
        return false;
    }

    public void liberarLoteAssincrono(Long loteId, Long analistaId) {
        if (transicaoService != null) {
            transicaoService.liberarLoteParaSilverAssincrono(loteId, analistaId);
        }
    }

    public boolean descartarLote(Long loteId, String motivo, Long analistaId) {
        if (transicaoService != null) {
            return transicaoService.descartarLote(loteId, motivo, analistaId);
        }
        return false;
    }

    public List<ItemQuarentenaDto> listarItens(String entidade, String status, String tipoErro, Long fonteDadoId, int offset, int limit) {
        return listarItens(entidade, status, tipoErro, fonteDadoId, null, offset, limit);
    }

    /**
     * Lista os itens retidos em quarentena de acordo com filtros opcionais.
     */
    public List<ItemQuarentenaDto> listarItens(String entidade, String status, String tipoErro, Long fonteDadoId, Long loteIngestaoId, int offset, int limit) {
        List<String> entidadesParaConsultar = resolverEntidades(entidade);
        if (entidadesParaConsultar.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> subqueries = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        for (String ent : entidadesParaConsultar) {
            TabelaEspelhoConfig config = TABELAS_ESPELHO.get(ent);
            if (config == null) continue;

            StringBuilder sb = new StringBuilder();
            sb.append("SELECT q.id, '").append(ent).append("' AS entidade, ")
              .append("COALESCE(").append(config.expressaoChave()).append(", 'ID-' || q.id) AS chave_identificacao, ")
              .append("q.fonte_dado_id, fd.nome AS fonte_dado_nome, q.lote_ingestao_id, q.registro_bruto_id, ")
              .append("te.codigo AS tipo_erro_codigo, te.descricao AS tipo_erro_descricao, ")
              .append("q.campo_problema, q.valor_recebido, q.motivo_rejeicao, ")
              .append("sa.codigo AS status_analise_codigo, sa.descricao AS status_analise_descricao, ")
              .append("q.data_quarentena, q.data_resolucao, q.geometria_wkt, JSON_SERIALIZE(q.payload_original RETURNING CLOB) AS payload_original ")
              .append("FROM QUARENTENA.").append(config.nomeTabela()).append(" q ")
              .append("LEFT JOIN QUARENTENA.tipo_erro_quarentena te ON te.id = q.tipo_erro_id ")
              .append("LEFT JOIN QUARENTENA.status_analise_quarentena sa ON sa.id = q.status_analise_id ")
              .append("LEFT JOIN CONTROLE.fonte_dado fd ON fd.id = q.fonte_dado_id ")
              .append("WHERE 1=1 ");

            if (status != null && !status.isBlank() && !"todos".equalsIgnoreCase(status.trim()) && !"todas".equalsIgnoreCase(status.trim())) {
                sb.append("AND sa.codigo = ? ");
                params.add(status.trim().toUpperCase());
            }
            if (tipoErro != null && !tipoErro.isBlank() && !"todos".equalsIgnoreCase(tipoErro.trim()) && !"todas".equalsIgnoreCase(tipoErro.trim())) {
                sb.append("AND te.codigo = ? ");
                params.add(tipoErro.trim().toUpperCase());
            }
            if (fonteDadoId != null) {
                sb.append("AND q.fonte_dado_id = ? ");
                params.add(fonteDadoId);
            }
            if (loteIngestaoId != null) {
                sb.append("AND q.lote_ingestao_id = ? ");
                params.add(loteIngestaoId);
            }

            subqueries.add(sb.toString());
        }

        if (subqueries.isEmpty()) {
            return Collections.emptyList();
        }

        String sqlUnificada = String.join(" UNION ALL ", subqueries) + " ORDER BY data_quarentena DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        params.add(Math.max(0, offset));
        params.add(Math.max(1, limit));

        try {
            return jdbcTemplate.query(sqlUnificada, (rs, rowNum) -> mapearItemDto(rs), params.toArray());
        } catch (Exception e) {
            log.error("Erro ao consultar quarentena com sintaxe FETCH: {}", e.getMessage(), e);
            throw new RuntimeException("Falha ao consultar quarentena: " + e.getMessage(), e);
        }
    }

    /**
     * Conta a quantidade total de itens retidos de acordo com os filtros aplicados.
     */
    public long contarItens(String entidade, String status, String tipoErro, Long fonteDadoId, Long loteIngestaoId) {
        List<String> entidadesParaConsultar = resolverEntidades(entidade);
        if (entidadesParaConsultar.isEmpty()) {
            return 0;
        }

        List<String> subqueries = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        for (String ent : entidadesParaConsultar) {
            TabelaEspelhoConfig config = TABELAS_ESPELHO.get(ent);
            if (config == null) continue;

            StringBuilder sb = new StringBuilder();
            sb.append("SELECT COUNT(1) AS qtd FROM QUARENTENA.").append(config.nomeTabela()).append(" q ")
              .append("LEFT JOIN QUARENTENA.tipo_erro_quarentena te ON te.id = q.tipo_erro_id ")
              .append("LEFT JOIN QUARENTENA.status_analise_quarentena sa ON sa.id = q.status_analise_id ")
              .append("WHERE 1=1 ");

            if (status != null && !status.isBlank() && !"todos".equalsIgnoreCase(status.trim()) && !"todas".equalsIgnoreCase(status.trim())) {
                sb.append("AND sa.codigo = ? ");
                params.add(status.trim().toUpperCase());
            }
            if (tipoErro != null && !tipoErro.isBlank() && !"todos".equalsIgnoreCase(tipoErro.trim()) && !"todas".equalsIgnoreCase(tipoErro.trim())) {
                sb.append("AND te.codigo = ? ");
                params.add(tipoErro.trim().toUpperCase());
            }
            if (fonteDadoId != null) {
                sb.append("AND q.fonte_dado_id = ? ");
                params.add(fonteDadoId);
            }
            if (loteIngestaoId != null) {
                sb.append("AND q.lote_ingestao_id = ? ");
                params.add(loteIngestaoId);
            }

            subqueries.add(sb.toString());
        }

        if (subqueries.isEmpty()) {
            return 0;
        }

        String sql = "SELECT COALESCE(SUM(qtd), 0) FROM (" + String.join(" UNION ALL ", subqueries) + ")";
        try {
            Long total = jdbcTemplate.queryForObject(sql, Long.class, params.toArray());
            return total != null ? total : 0;
        } catch (Exception e) {
            log.error("Erro ao contar itens da quarentena: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Retorna métricas agregadas da Quarentena para os dashboards do frontend.
     */
    public MetricasQuarentenaDto obterMetricas() {
        long totalRegistros = 0;
        long totalPendentes = 0;
        long totalEmAnalise = 0;
        long totalCorrigidos = 0;
        long totalDescartados = 0;
        Map<String, Long> porTipoErro = new HashMap<>();
        Map<String, Long> porEntidade = new HashMap<>();

        for (Map.Entry<String, TabelaEspelhoConfig> entry : TABELAS_ESPELHO.entrySet()) {
            String ent = entry.getKey();
            TabelaEspelhoConfig cfg = entry.getValue();

            String sql = "SELECT sa.codigo AS status, te.codigo AS tipo_erro, COUNT(1) AS qtd " +
                         "FROM QUARENTENA." + cfg.nomeTabela() + " q " +
                         "LEFT JOIN QUARENTENA.status_analise_quarentena sa ON sa.id = q.status_analise_id " +
                         "LEFT JOIN QUARENTENA.tipo_erro_quarentena te ON te.id = q.tipo_erro_id " +
                         "GROUP BY sa.codigo, te.codigo";

            try {
                long totalDestaEntidade = 0;
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
                for (Map<String, Object> r : rows) {
                    String st = (String) r.get("status");
                    String te = (String) r.get("tipo_erro");
                    long qtd = ((Number) r.get("qtd")).longValue();

                    totalRegistros += qtd;
                    totalDestaEntidade += qtd;

                    if ("PENDENTE".equalsIgnoreCase(st)) totalPendentes += qtd;
                    else if ("EM_ANALISE".equalsIgnoreCase(st)) totalEmAnalise += qtd;
                    else if ("CORRIGIDO_REPROCESSADO".equalsIgnoreCase(st)) totalCorrigidos += qtd;
                    else if ("DESCARTADO".equalsIgnoreCase(st)) totalDescartados += qtd;

                    if (te != null) {
                        porTipoErro.merge(te, qtd, Long::sum);
                    }
                }
                porEntidade.put(ent, totalDestaEntidade);
            } catch (Exception e) {
                log.debug("Tabela {} ainda sem registros ou inacessível: {}", cfg.nomeTabela(), e.getMessage());
                porEntidade.put(ent, 0L);
            }
        }

        return new MetricasQuarentenaDto(
                totalRegistros,
                totalPendentes,
                totalEmAnalise,
                totalCorrigidos,
                totalDescartados,
                porTipoErro,
                porEntidade
        );
    }

    /**
     * Retorna o diagnóstico de erros agregados na Quarentena com filtros opcionais de dataset e período.
     */
    public List<DiagnosticoErroDto> obterDiagnosticoPorErro(String dataset, LocalDate dataInicio, LocalDate dataFim) {
        List<String> entidades = resolverEntidades(dataset);
        if (entidades.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Long> contagemPorErro = new HashMap<>();

        for (String ent : entidades) {
            TabelaEspelhoConfig cfg = TABELAS_ESPELHO.get(ent);
            if (cfg == null) continue;

            StringBuilder sb = new StringBuilder();
            sb.append("SELECT te.codigo AS tipo_erro, COUNT(1) AS qtd ")
              .append("FROM QUARENTENA.").append(cfg.nomeTabela()).append(" q ")
              .append("LEFT JOIN QUARENTENA.tipo_erro_quarentena te ON te.id = q.tipo_erro_id ")
              .append("WHERE 1=1 ");

            List<Object> params = new ArrayList<>();
            if (dataInicio != null) {
                sb.append("AND q.data_quarentena >= ? ");
                params.add(Timestamp.valueOf(dataInicio.atStartOfDay()));
            }
            if (dataFim != null) {
                sb.append("AND q.data_quarentena < ? ");
                params.add(Timestamp.valueOf(dataFim.plusDays(1).atStartOfDay()));
            }

            sb.append("GROUP BY te.codigo");

            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sb.toString(), params.toArray());
                for (Map<String, Object> r : rows) {
                    String te = (String) r.get("tipo_erro");
                    if (te == null || te.isBlank()) {
                        te = "OUTROS";
                    }
                    long qtd = ((Number) r.get("qtd")).longValue();
                    contagemPorErro.merge(te, qtd, Long::sum);
                }
            } catch (Exception e) {
                log.debug("Erro ao consultar diagnóstico na tabela {}: {}", cfg.nomeTabela(), e.getMessage());
            }
        }

        return contagemPorErro.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new DiagnosticoErroDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * Retorna a lista de tipos de erros possíveis mapeados na Quarentena.
     */
    public List<TipoErroDto> listarTiposErro() {
        String sql = "SELECT id, codigo, descricao FROM QUARENTENA.tipo_erro_quarentena WHERE ativo = 1 ORDER BY codigo";
        return jdbcTemplate.query(sql, (rs, rowNum) ->
                new TipoErroDto(rs.getLong("id"), rs.getString("codigo"), rs.getString("descricao")));
    }

    /**
     * Retorna os status de ciclo de vida de um item em Quarentena.
     */
    public List<StatusAnaliseDto> listarStatusAnalise() {
        String sql = "SELECT id, codigo, descricao FROM QUARENTENA.status_analise_quarentena WHERE ativo = 1 ORDER BY id";
        return jdbcTemplate.query(sql, (rs, rowNum) ->
                new StatusAnaliseDto(rs.getLong("id"), rs.getString("codigo"), rs.getString("descricao")));
    }

    /**
     * Busca um item específico em uma tabela espelho pelo ID.
     */
    public ItemQuarentenaDto buscarPorId(String entidade, Long id) {
        TabelaEspelhoConfig cfg = TABELAS_ESPELHO.get(normalizarEntidade(entidade));
        if (cfg == null) {
            throw new IllegalArgumentException("Entidade de quarentena desconhecida: " + entidade);
        }

        String sql = "SELECT q.id, '" + entidade + "' AS entidade, " +
                     "COALESCE(" + cfg.expressaoChave() + ", 'ID-' || q.id) AS chave_identificacao, " +
                     "q.fonte_dado_id, fd.nome AS fonte_dado_nome, q.lote_ingestao_id, q.registro_bruto_id, " +
                     "te.codigo AS tipo_erro_codigo, te.descricao AS tipo_erro_descricao, " +
                     "q.campo_problema, q.valor_recebido, q.motivo_rejeicao, " +
                     "sa.codigo AS status_analise_codigo, sa.descricao AS status_analise_descricao, " +
                     "q.data_quarentena, q.data_resolucao, q.geometria_wkt, JSON_SERIALIZE(q.payload_original RETURNING CLOB) AS payload_original " +
                     "FROM QUARENTENA." + cfg.nomeTabela() + " q " +
                     "LEFT JOIN QUARENTENA.tipo_erro_quarentena te ON te.id = q.tipo_erro_id " +
                     "LEFT JOIN QUARENTENA.status_analise_quarentena sa ON sa.id = q.status_analise_id " +
                     "LEFT JOIN CONTROLE.fonte_dado fd ON fd.id = q.fonte_dado_id " +
                     "WHERE q.id = ?";

        List<ItemQuarentenaDto> resultados = jdbcTemplate.query(sql, (rs, rowNum) -> mapearItemDto(rs), id);
        return resultados.isEmpty() ? null : resultados.get(0);
    }

    /**
     * Marca um item de quarentena como DESCARTADO com justificativa do operador.
     */
    @Transactional
    public boolean descartar(String entidade, Long id, AcaoQuarentenaRequest request) {
        TabelaEspelhoConfig cfg = TABELAS_ESPELHO.get(normalizarEntidade(entidade));
        if (cfg == null) {
            throw new IllegalArgumentException("Entidade de quarentena desconhecida: " + entidade);
        }

        String motivoDesc = request != null && request.motivo() != null && !request.motivo().isBlank()
                ? " | Descartado: " + request.motivo()
                : " | Descartado pelo analista";

        String sql = "UPDATE QUARENTENA." + cfg.nomeTabela() + " SET " +
                     "status_analise_id = (SELECT id FROM QUARENTENA.status_analise_quarentena WHERE codigo = 'DESCARTADO'), " +
                     "motivo_rejeicao = SUBSTR(COALESCE(motivo_rejeicao, '') || ?, 1, 500), " +
                     "analista_responsavel_id = ?, " +
                     "data_resolucao = SYSTIMESTAMP " +
                     "WHERE id = ?";

        Long analistaId = request != null ? request.analistaId() : null;
        int rows = jdbcTemplate.update(sql, motivoDesc, analistaId, id);

        if (rows > 0 && transicaoService != null) {
            try {
                String sqlLote = "SELECT lote_ingestao_id FROM QUARENTENA." + cfg.nomeTabela() + " WHERE id = ?";
                List<Long> loteIds = jdbcTemplate.query(sqlLote, (rs, rowNum) -> rs.getLong(1), id);
                if (!loteIds.isEmpty() && loteIds.get(0) != null) {
                    transicaoService.verificarEAtualizarStatusLoteAposResolucao(loteIds.get(0), analistaId);
                }
            } catch (Exception e) {
                log.debug("Aviso ao verificar status do lote após descarte: {}", e.getMessage());
            }
        }

        return rows > 0;
    }

    /**
     * Altera o status para EM_ANALISE.
     */
    @Transactional
    public boolean iniciarAnalise(String entidade, Long id, Long analistaId) {
        TabelaEspelhoConfig cfg = TABELAS_ESPELHO.get(normalizarEntidade(entidade));
        if (cfg == null) {
            throw new IllegalArgumentException("Entidade de quarentena desconhecida: " + entidade);
        }

        String sql = "UPDATE QUARENTENA." + cfg.nomeTabela() + " SET " +
                     "status_analise_id = (SELECT id FROM QUARENTENA.status_analise_quarentena WHERE codigo = 'EM_ANALISE'), " +
                     "analista_responsavel_id = ? " +
                     "WHERE id = ?";

        return jdbcTemplate.update(sql, analistaId, id) > 0;
    }

    /**
     * Registra que o item foi corrigido e promove-o para a camada Silver.
     * <p>
     * Se houver falha de validação, uma IllegalArgumentException é lançada e não deve
     * marcar a transação como rollback-only prematuramente.
     */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public ResultadoRevalidacaoQuarentena marcarCorrigido(String entidade, Long id, AcaoQuarentenaRequest request) {
        if (transicaoService != null) {
            return transicaoService.promoverRegistroQuarentenaParaSilver(entidade, id, request);
        }

        TabelaEspelhoConfig cfg = TABELAS_ESPELHO.get(normalizarEntidade(entidade));
        if (cfg == null) {
            throw new IllegalArgumentException("Entidade de quarentena desconhecida: " + entidade);
        }

        Long analistaId = request != null ? request.analistaId() : null;
        String sql = "UPDATE QUARENTENA." + cfg.nomeTabela() + " SET " +
                     "status_analise_id = (SELECT id FROM QUARENTENA.status_analise_quarentena WHERE codigo = 'CORRIGIDO_REPROCESSADO'), " +
                     "analista_responsavel_id = ?, " +
                     "data_resolucao = SYSTIMESTAMP " +
                     "WHERE id = ?";

        int rows = jdbcTemplate.update(sql, analistaId, id);
        if (rows > 0) {
            return ResultadoRevalidacaoQuarentena.individual(id, List.of());
        }
        return null;
    }

    /**
     * Sobrecarga para compatibilidade com chamadas legadas passando apenas analistaId.
     */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public ResultadoRevalidacaoQuarentena marcarCorrigido(String entidade, Long id, Long analistaId) {
        return marcarCorrigido(entidade, id, new AcaoQuarentenaRequest(null, null, analistaId));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<String> resolverEntidades(String entidade) {
        if (entidade == null || entidade.isBlank() || "todas".equalsIgnoreCase(entidade) || "todos".equalsIgnoreCase(entidade)) {
            return new ArrayList<>(TABELAS_ESPELHO.keySet());
        }
        String normalizada = normalizarEntidade(entidade);
        return TABELAS_ESPELHO.containsKey(normalizada) ? List.of(normalizada) : Collections.emptyList();
    }

    private String normalizarEntidade(String ent) {
        if (ent == null) return "";
        String s = ent.trim().toLowerCase();
        if (s.startsWith("q_")) s = s.substring(2);
        return s;
    }

    /**
     * Adiciona um novo valor de domínio via DominioService.
     */
    public Map<String, Object> adicionarValorDominio(NovoDominioRequest request) {
        if (dominioService != null) {
            return dominioService.adicionarValorDominio(request);
        }
        throw new IllegalStateException("Serviço de Domínio não configurado.");
    }

    /**
     * Lista os tipos de áreas protegidas cadastrados na Silver.
     */
    public List<Map<String, Object>> listarTiposAreaProtegida() {
        if (dominioService != null) {
            return dominioService.listarTiposAreaProtegida();
        }
        return Collections.emptyList();
    }

    private ItemQuarentenaDto mapearItemDto(ResultSet rs) throws SQLException {
        LocalDateTime dtQuarentena = rs.getTimestamp("data_quarentena") != null
                ? rs.getTimestamp("data_quarentena").toLocalDateTime()
                : null;
        LocalDateTime dtResolucao = rs.getTimestamp("data_resolucao") != null
                ? rs.getTimestamp("data_resolucao").toLocalDateTime()
                : null;

        String payload = null;
        try {
            java.sql.Clob clob = rs.getClob("payload_original");
            if (clob != null) {
                payload = clob.getSubString(1, (int) Math.min(clob.length(), Integer.MAX_VALUE));
            }
        } catch (Exception e) {
            try {
                payload = rs.getString("payload_original");
            } catch (Exception ignored) {}
        }

        String tipoErro = rs.getString("tipo_erro_codigo");
        String motivo = rs.getString("motivo_rejeicao");
        String campoProb = rs.getString("campo_problema");
        String valRecebido = rs.getString("valor_recebido");
        String entidade = rs.getString("entidade");

        String tabelaRef = null;
        if ("VALOR_FORA_DO_DOMINIO".equalsIgnoreCase(tipoErro)) {
            if (motivo != null) {
                Matcher m = PATTERN_TABELA_REF.matcher(motivo);
                if (m.find()) {
                    tabelaRef = ValidadorDominio.normalizarAliasTabela(m.group(1));
                }
            }
            if (tabelaRef == null && campoProb != null) {
                tabelaRef = OrquestradorValidacao.inferirTabelaReferenciaCanonico(campoProb);
                if (tabelaRef == null) {
                    tabelaRef = ValidadorDominio.normalizarAliasTabela(campoProb);
                }
            }
        }

        String tipoAreaProtegida = null;
        String categoriaAreaProtegida = null;
        if ("area_protegida".equalsIgnoreCase(entidade)) {
            if (payload != null) {
                Matcher mTipo = PATTERN_TIPO_AP.matcher(payload);
                if (mTipo.find()) {
                    tipoAreaProtegida = mTipo.group(1);
                }
                Matcher mCat = PATTERN_CAT_AP.matcher(payload);
                if (mCat.find()) {
                    categoriaAreaProtegida = mCat.group(1);
                }
            }
            if (tipoAreaProtegida == null && "tipo_area_protegida".equalsIgnoreCase(campoProb)) {
                tipoAreaProtegida = valRecebido;
            }
            if (categoriaAreaProtegida == null && "categoria_area_protegida".equalsIgnoreCase(campoProb)) {
                categoriaAreaProtegida = valRecebido;
            }
        }

        String geomWkt = null;
        try {
            geomWkt = rs.getString("geometria_wkt");
        } catch (Exception ignored) {
        }
        if ((geomWkt == null || geomWkt.isBlank()) && valRecebido != null) {
            String valTrim = valRecebido.trim().toUpperCase(java.util.Locale.ROOT);
            if (valTrim.startsWith("POLYGON") || valTrim.startsWith("MULTIPOLYGON") || valTrim.startsWith("POINT") || valTrim.startsWith("LINESTRING")) {
                geomWkt = valRecebido.trim();
            }
        }

        return new ItemQuarentenaDto(
                rs.getLong("id"),
                entidade,
                rs.getString("chave_identificacao"),
                rs.getLong("fonte_dado_id"),
                rs.getString("fonte_dado_nome"),
                rs.getLong("lote_ingestao_id"),
                rs.getLong("registro_bruto_id"),
                tipoErro,
                rs.getString("tipo_erro_descricao"),
                campoProb,
                valRecebido,
                motivo,
                rs.getString("status_analise_codigo"),
                rs.getString("status_analise_descricao"),
                dtQuarentena != null ? dtQuarentena.format(ISO_FORMATTER) : null,
                dtResolucao != null ? dtResolucao.format(ISO_FORMATTER) : null,
                payload,
                tabelaRef,
                tipoAreaProtegida,
                categoriaAreaProtegida,
                geomWkt
        );
    }
}

