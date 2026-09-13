package br.gov.sp.cps.datagis.quarentena;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuarentenaService {

    private static final Logger log = LoggerFactory.getLogger(QuarentenaService.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

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
    }

    public record TabelaEspelhoConfig(String nomeTabela, String expressaoChave) {}

    public QuarentenaService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Lista os itens retidos em quarentena de acordo com filtros opcionais.
     */
    public List<ItemQuarentenaDto> listarItens(String entidade, String status, String tipoErro, Long fonteDadoId, int offset, int limit) {
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
              .append("q.data_quarentena, q.data_resolucao, CAST(q.payload_original AS VARCHAR2(4000)) AS payload_original ")
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
            log.warn("Erro ao consultar quarentena com sintaxe FETCH: {}, tentando fallback LIMIT/OFFSET", e.getMessage());
            return fallbackConsulta(subqueries, params, offset, limit);
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
                     "q.data_quarentena, q.data_resolucao, CAST(q.payload_original AS VARCHAR2(4000)) AS payload_original " +
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
     * Registra que o item foi corrigido e reprocessado.
     */
    @Transactional
    public boolean marcarCorrigido(String entidade, Long id, Long analistaId) {
        TabelaEspelhoConfig cfg = TABELAS_ESPELHO.get(normalizarEntidade(entidade));
        if (cfg == null) {
            throw new IllegalArgumentException("Entidade de quarentena desconhecida: " + entidade);
        }

        String sql = "UPDATE QUARENTENA." + cfg.nomeTabela() + " SET " +
                     "status_analise_id = (SELECT id FROM QUARENTENA.status_analise_quarentena WHERE codigo = 'CORRIGIDO_REPROCESSADO'), " +
                     "analista_responsavel_id = ?, " +
                     "data_resolucao = SYSTIMESTAMP " +
                     "WHERE id = ?";

        return jdbcTemplate.update(sql, analistaId, id) > 0;
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

    private ItemQuarentenaDto mapearItemDto(ResultSet rs) throws SQLException {
        LocalDateTime dtQuarentena = rs.getTimestamp("data_quarentena") != null
                ? rs.getTimestamp("data_quarentena").toLocalDateTime()
                : null;
        LocalDateTime dtResolucao = rs.getTimestamp("data_resolucao") != null
                ? rs.getTimestamp("data_resolucao").toLocalDateTime()
                : null;

        return new ItemQuarentenaDto(
                rs.getLong("id"),
                rs.getString("entidade"),
                rs.getString("chave_identificacao"),
                rs.getLong("fonte_dado_id"),
                rs.getString("fonte_dado_nome"),
                rs.getLong("lote_ingestao_id"),
                rs.getLong("registro_bruto_id"),
                rs.getString("tipo_erro_codigo"),
                rs.getString("tipo_erro_descricao"),
                rs.getString("campo_problema"),
                rs.getString("valor_recebido"),
                rs.getString("motivo_rejeicao"),
                rs.getString("status_analise_codigo"),
                rs.getString("status_analise_descricao"),
                dtQuarentena != null ? dtQuarentena.format(ISO_FORMATTER) : null,
                dtResolucao != null ? dtResolucao.format(ISO_FORMATTER) : null,
                rs.getString("payload_original")
        );
    }

    private List<ItemQuarentenaDto> fallbackConsulta(List<String> subqueries, List<Object> params, int offset, int limit) {
        // Fallback para ambientes com suporte básico
        String sql = String.join(" UNION ALL ", subqueries);
        List<Object> cleanParams = new ArrayList<>(params.subList(0, params.size() - 2));
        List<ItemQuarentenaDto> todos = jdbcTemplate.query(sql, (rs, rowNum) -> mapearItemDto(rs), cleanParams.toArray());
        int start = Math.min(Math.max(0, offset), todos.size());
        int end = Math.min(start + Math.max(1, limit), todos.size());
        return todos.subList(start, end);
    }
}
