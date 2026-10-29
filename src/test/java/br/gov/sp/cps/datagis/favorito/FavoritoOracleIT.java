package br.gov.sp.cps.datagis.favorito;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** Oracle local real (V089 aplicada). Cada teste faz rollback. */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.quartz.auto-startup=false",
        "spring.batch.job.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "DATAGIS_ORACLE_IT", matches = "true")
@Transactional
class FavoritoOracleIT {

    private static final String MARIA = "uuid-teste-favorito-maria";
    private static final String JOAO = "uuid-teste-favorito-joao";

    @Autowired private FavoritoRepositorio repositorio;

    @Test
    void guardaNaOrdemETrocaAListaInteira() {
        repositorio.substituir(MARIA, List.of("/lotes", "/fontes", "/quarentena"));
        assertThat(repositorio.listar(MARIA)).containsExactly("/lotes", "/fontes", "/quarentena");

        repositorio.substituir(MARIA, List.of("/quarentena", "/lotes"));
        assertThat(repositorio.listar(MARIA)).containsExactly("/quarentena", "/lotes");

        repositorio.substituir(MARIA, List.of());
        assertThat(repositorio.listar(MARIA)).isEmpty();
    }

    @Test
    void cadaUsuarioVeSoOsProprios() {
        repositorio.substituir(MARIA, List.of("/fontes"));
        repositorio.substituir(JOAO, List.of("/auditoria"));

        assertThat(repositorio.listar(MARIA)).containsExactly("/fontes");
        assertThat(repositorio.listar(JOAO)).containsExactly("/auditoria");
    }
}
