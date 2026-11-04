package br.gov.sp.cps.datagis.seguranca;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acesso a {@code controle.token_acesso_usuario} (V090): se o administrador liberou a
 * criacao de token de acesso para o usuario. Sem linha = nao liberado.
 */
@Repository
public class TokenAcessoRepositorio {

    private final JdbcTemplate jdbc;

    public TokenAcessoRepositorio(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean habilitado(String usuarioId) {
        List<Integer> valores = jdbc.queryForList(
                "SELECT habilitado FROM token_acesso_usuario WHERE usuario_id = ?", Integer.class, usuarioId);
        return !valores.isEmpty() && valores.get(0) == 1;
    }

    public void definir(String usuarioId, boolean habilitado, String alteradoPor) {
        jdbc.update("""
                MERGE INTO token_acesso_usuario t
                USING (SELECT ? AS usuario_id FROM dual) n ON (t.usuario_id = n.usuario_id)
                WHEN MATCHED THEN UPDATE SET habilitado = ?, alterado_por = ?, data_alt = SYSTIMESTAMP
                WHEN NOT MATCHED THEN INSERT (usuario_id, habilitado, alterado_por) VALUES (n.usuario_id, ?, ?)
                """, usuarioId, habilitado ? 1 : 0, alteradoPor, habilitado ? 1 : 0, alteradoPor);
    }
}
