package br.gov.sp.cps.datagis.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Confere os arquivos do tema de login (LIZARDS-61) sem subir o Keycloak: um nome de
 * tema ou de arquivo digitado errado nao da erro em lugar nenhum - o Keycloak so volta
 * pro tema padrao, ou a imagem some da tela.
 */
class TemaLoginKeycloakTest {

    private static final Path TEMA = Path.of("infra/keycloak-tema/datagis/login");

    @Test
    void temaHerdaOPadraoDoKeycloakEAcrescentaOProprioCss() throws IOException {
        Properties tema = carregar(TEMA.resolve("theme.properties"));

        assertThat(tema.getProperty("parent")).isEqualTo("keycloak.v2");
        // O styles.css do pai precisa continuar na lista: sem ele o layout do keycloak.v2 se perde
        assertThat(tema.getProperty("styles").split("\\s+")).containsExactly("css/styles.css", "css/datagis.css");
        assertThat(TEMA.resolve("resources/css/datagis.css")).isRegularFile();
    }

    @Test
    void faviconDoTemaSubstituiODoKeycloak() throws IOException {
        // O template.ftl do keycloak.v2 procura img/favicon.ico no tema; sem ele, vale o do pai
        byte[] icone = Files.readAllBytes(TEMA.resolve("resources/img/favicon.ico"));

        // cabecalho ICO: reservado = 0, tipo = 1 (icone)
        assertThat(icone).startsWith(0, 0, 1, 0);
    }

    @Test
    void scriptDoTemaExisteEPreparaOsCamposDoRotuloFlutuante() throws IOException {
        Properties tema = carregar(TEMA.resolve("theme.properties"));
        Path script = TEMA.resolve("resources").resolve(tema.getProperty("scripts"));

        assertThat(script).isRegularFile();
        // ids dos campos montados pelo field.ftl do Keycloak 26 (login e troca de senha)
        assertThat(Files.readString(script)).contains("'username'", "'password'", "'password-new'", "'password-confirm'");
    }

    @Test
    void todaImagemCitadaNoCssExiste() throws IOException {
        String css = Files.readString(TEMA.resolve("resources/css/datagis.css"));
        Matcher url = Pattern.compile("url\\(\"\\.\\./(img/[^\"]+)\"\\)").matcher(css);

        int imagens = 0;
        while (url.find()) {
            assertThat(TEMA.resolve("resources").resolve(url.group(1))).isRegularFile();
            imagens++;
        }
        assertThat(imagens).as("fundo e logo").isEqualTo(2);
    }

    @Test
    void realmEProvisionadorApontamParaOMesmoTema() throws IOException {
        String nomeDaPasta = TEMA.getParent().getFileName().toString();
        String realm = Files.readString(Path.of("infra/keycloak/realm-datagis.json"));
        Properties app = carregar(Path.of("src/main/resources/application.properties"));

        assertThat(realm).contains("\"loginTheme\": \"" + nomeDaPasta + "\"");
        assertThat(app.getProperty("app.keycloak.tema-login")).isEqualTo(nomeDaPasta);
    }

    @Test
    void composeMontaOTemaNaPastaDeTemasDoKeycloak() throws IOException {
        String compose = Files.readString(Path.of("docker-compose.yml"));

        assertThat(compose).contains("./infra/keycloak-tema/datagis:/opt/keycloak/themes/datagis:ro");
    }

    private static Properties carregar(Path arquivo) throws IOException {
        Properties props = new Properties();
        try (Reader leitor = Files.newBufferedReader(arquivo)) {
            props.load(leitor);
        }
        return props;
    }
}
