package br.gov.sp.cps.datagis.auditoria;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Acesso a {@code controle.log_auditoria} (V075). So INSERT e SELECT - a tabela recusa
 * UPDATE/DELETE por trigger (RN025). JdbcTemplate em vez de JPA: e uma tabela de
 * apendice, sem relacionamento, e a consulta tem filtros opcionais montados na hora.
 */
@Repository
public class LogAuditoriaRepositorio {

    private static final RowMapper<LogAuditoriaResponse> MAPEADOR = (rs, i) -> new LogAuditoriaResponse(
            rs.getLong("id"),
            rs.getTimestamp("data_hora").toInstant(),
            rs.getString("usuario_id"),
            rs.getString("usuario_login"),
            rs.getString("perfis"),
            rs.getString("metodo"),
            rs.getString("rota"),
            rs.getString("rota_padrao"),
            rs.getString("parametros"),
            rs.getString("acao"),
            rs.getInt("status_http"),
            rs.getInt("sucesso") == 1,
            rs.getObject("duracao_ms") == null ? null : rs.getLong("duracao_ms"),
            rs.getString("ip_origem"));

    private final JdbcTemplate jdbc;

    public LogAuditoriaRepositorio(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void registrar(RegistroAuditoria r) {
        jdbc.update("""
                INSERT INTO log_auditoria
                    (data_hora, usuario_id, usuario_login, perfis, metodo, rota, rota_padrao,
                     parametros, acao, status_http, sucesso, duracao_ms, ip_origem)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                Timestamp.from(r.dataHora()), r.usuarioId(), cortar(r.usuarioLogin(), 200), cortar(r.perfis(), 1000),
                r.metodo(), cortar(r.rota(), 1000), r.rotaPadrao(), cortar(r.parametros(), 2000), r.acao(),
                r.statusHttp(), r.sucesso() ? 1 : 0, r.duracaoMs(), cortar(r.ipOrigem(), 64));
    }

    /** Mais recentes primeiro. Todos os filtros sao opcionais. */
    public PaginaAuditoria buscar(FiltroConsultaAuditoria filtro, int pagina, int tamanho) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> parametros = new ArrayList<>();
        if (temTexto(filtro.usuario())) {
            where.append(" AND LOWER(usuario_login) LIKE ?");
            parametros.add("%" + filtro.usuario().trim().toLowerCase() + "%");
        }
        if (temTexto(filtro.metodo())) {
            where.append(" AND metodo = ?");
            parametros.add(filtro.metodo().trim().toUpperCase());
        }
        if (filtro.sucesso() != null) {
            where.append(" AND sucesso = ?");
            parametros.add(filtro.sucesso() ? 1 : 0);
        }
        if (temTexto(filtro.texto())) {
            where.append(" AND (LOWER(acao) LIKE ? OR LOWER(rota) LIKE ?)");
            String termo = "%" + filtro.texto().trim().toLowerCase() + "%";
            parametros.add(termo);
            parametros.add(termo);
        }
        if (filtro.de() != null) {
            where.append(" AND data_hora >= ?");
            parametros.add(Timestamp.valueOf(filtro.de().atStartOfDay()));
        }
        if (filtro.ate() != null) {
            // "ate" inclui o dia inteiro
            where.append(" AND data_hora < ?");
            parametros.add(Timestamp.valueOf(filtro.ate().plusDays(1).atStartOfDay()));
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM log_auditoria" + where, Long.class, parametros.toArray());

        List<Object> paginados = new ArrayList<>(parametros);
        paginados.add((long) pagina * tamanho);
        paginados.add(tamanho);
        List<LogAuditoriaResponse> itens = jdbc.query(
                "SELECT * FROM log_auditoria" + where + " ORDER BY data_hora DESC, id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                MAPEADOR, paginados.toArray());
        return new PaginaAuditoria(itens, total == null ? 0 : total, pagina, tamanho);
    }

    private static boolean temTexto(String valor) {
        return valor != null && !valor.isBlank();
    }

    private static String cortar(String valor, int limite) {
        return valor == null || valor.length() <= limite ? valor : valor.substring(0, limite);
    }

    /** Linha a gravar. */
    public record RegistroAuditoria(
            Instant dataHora, String usuarioId, String usuarioLogin, String perfis, String metodo, String rota,
            String rotaPadrao, String parametros, String acao, int statusHttp, boolean sucesso, Long duracaoMs,
            String ipOrigem) {
    }

    public record FiltroConsultaAuditoria(
            String usuario, String metodo, Boolean sucesso, String texto, LocalDate de, LocalDate ate) {
    }

    public record LogAuditoriaResponse(
            long id, Instant dataHora, String usuarioId, String usuarioLogin, String perfis, String metodo,
            String rota, String rotaPadrao, String parametros, String acao, int statusHttp, boolean sucesso,
            Long duracaoMs, String ipOrigem) {
    }

    public record PaginaAuditoria(List<LogAuditoriaResponse> itens, long total, int pagina, int tamanho) {
    }
}
