package br.gov.sp.cps.datagis.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** Oracle local real (V090 aplicada). Cada teste faz rollback. */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.quartz.auto-startup=false",
        "spring.batch.job.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "DATAGIS_ORACLE_IT", matches = "true")
@Transactional
class TokenAcessoOracleIT {

    private static final String USUARIO = "uuid-teste-token-acesso";

    @Autowired private TokenAcessoRepositorio tokens;

    @Test
    void semLinhaNaoEstaLiberado() {
        assertThat(tokens.habilitado(USUARIO)).isFalse();
    }

    @Test
    void liberaEBloqueiaNaMesmaLinha() {
        tokens.definir(USUARIO, true, "admin");
        assertThat(tokens.habilitado(USUARIO)).isTrue();

        tokens.definir(USUARIO, false, "admin");
        assertThat(tokens.habilitado(USUARIO)).isFalse();
    }
}
