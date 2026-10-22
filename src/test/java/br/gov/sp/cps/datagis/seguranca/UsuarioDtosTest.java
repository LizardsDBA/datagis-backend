package br.gov.sp.cps.datagis.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sp.cps.datagis.seguranca.UsuarioDtos.UsuarioNovoRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UsuarioDtosTest {

    private final Validator validador = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @ValueSource(strings = {"maria.silva", "joao_souza", "ana-paula", "matheus16", "maria@datagis"})
    void aceitaLoginNoFormatoDoKeycloak(String login) {
        assertThat(violacoesDoLogin(login)).isEmpty();
    }

    /** Achado ao vivo: "teste view" (com espaco) era recusado pelo Keycloak com JSON cru na tela. */
    @ParameterizedTest
    @ValueSource(strings = {"teste view", "joão", "maria!", "a/b"})
    void recusaLoginComEspacoAcentoOuSimbolo(String login) {
        assertThat(violacoesDoLogin(login)).singleElement()
                .extracting(ConstraintViolation::getMessage)
                .asString().contains("sem espacos");
    }

    private Set<ConstraintViolation<UsuarioNovoRequest>> violacoesDoLogin(String login) {
        UsuarioNovoRequest dados = new UsuarioNovoRequest(login, "Maria", "Silva", "maria@x.com", "Senha@123", List.of());
        return validador.validateProperty(dados, "login");
    }
}
