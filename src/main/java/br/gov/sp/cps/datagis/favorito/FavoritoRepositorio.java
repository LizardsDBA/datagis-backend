package br.gov.sp.cps.datagis.favorito;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acesso a {@code controle.favorito_usuario} (V089). JdbcTemplate em vez de JPA, como no
 * log de auditoria: tabela pequena, sem relacionamento, lida e trocada inteira por usuario.
 */
@Repository
public class FavoritoRepositorio {

    private final JdbcTemplate jdbc;

    public FavoritoRepositorio(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Rotas favoritas do usuario, na ordem escolhida por ele. */
    public List<String> listar(String usuarioId) {
        return jdbc.queryForList(
                "SELECT rota FROM favorito_usuario WHERE usuario_id = ? ORDER BY ordem, id",
                String.class, usuarioId);
    }

    /** Troca a lista inteira de uma vez: o que nao veio deixa de ser favorito. */
    @Transactional
    public void substituir(String usuarioId, List<String> rotas) {
        jdbc.update("DELETE FROM favorito_usuario WHERE usuario_id = ?", usuarioId);
        for (int i = 0; i < rotas.size(); i++) {
            jdbc.update("INSERT INTO favorito_usuario (usuario_id, rota, ordem) VALUES (?, ?, ?)",
                    usuarioId, rotas.get(i), i);
        }
    }
}
