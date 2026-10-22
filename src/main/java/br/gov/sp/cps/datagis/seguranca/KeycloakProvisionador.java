package br.gov.sp.cps.datagis.seguranca;

import br.gov.sp.cps.datagis.seguranca.KeycloakAdminClient.KcRole;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mantem o Keycloak igual ao catalogo do codigo ({@link Permissao} e {@link Perfil}), na
 * subida do backend:
 * <ol>
 *   <li>cria as roles de permissao que faltam (e corrige a descricao);</li>
 *   <li>cria as roles de perfil que faltam e acerta a composicao de cada uma - adiciona a
 *       permissao que entrou no perfil e REMOVE a que saiu;</li>
 *   <li>se {@code app.keycloak.usuarios-dev=true}, cria um usuario de teste por perfil
 *       (login = senha = {@link Perfil#usuarioDev()}). So cria: usuario ja existente nao e
 *       tocado, pra nao desfazer o que alguem mudou pela tela.</li>
 * </ol>
 *
 * <p>Por que no codigo e nao no JSON do realm: o {@code --import-realm} do Keycloak so
 * importa o realm na PRIMEIRA subida e ignora o arquivo depois. Com o catalogo aqui, um
 * PR que muda perfil/permissao chega em todas as maquinas no proximo restart do backend,
 * sem ninguem precisar apagar o volume do Keycloak (e perder os usuarios cadastrados).
 *
 * <p>Roda numa thread separada e com retentativa: o Keycloak pode ainda estar subindo, e
 * isso nao deve impedir o backend de subir (a ingestao agendada nao depende de login).
 */
@Component
@ConditionalOnProperty(name = "app.keycloak.provisionar", havingValue = "true")
public class KeycloakProvisionador implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KeycloakProvisionador.class);
    private static final int TENTATIVAS = 30;
    private static final long ESPERA_MS = 10_000;

    private final KeycloakAdminClient keycloak;
    private final KeycloakProperties props;

    public KeycloakProvisionador(KeycloakAdminClient keycloak, KeycloakProperties props) {
        this.keycloak = keycloak;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread thread = new Thread(this::sincronizarComRetentativa, "keycloak-provisionador");
        thread.setDaemon(true);
        thread.start();
    }

    private void sincronizarComRetentativa() {
        for (int tentativa = 1; tentativa <= TENTATIVAS; tentativa++) {
            try {
                sincronizar();
                return;
            } catch (RuntimeException e) {
                log.warn("Falha ao sincronizar roles com o Keycloak (tentativa {}/{}): {}",
                        tentativa, TENTATIVAS, e.getMessage());
                try {
                    Thread.sleep(ESPERA_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.error("Desisti de sincronizar roles com o Keycloak apos {} tentativas - login pode nao funcionar", TENTATIVAS);
    }

    /** Aplica o catalogo no Keycloak. Idempotente: rodar de novo sem mudanca nao altera nada. */
    public void sincronizar() {
        Map<String, KcRole> existentes = porNome(keycloak.listarRoles());

        for (Permissao p : Permissao.values()) {
            garantirRole(existentes, p.codigo(), p.descricao());
        }
        for (Perfil perfil : Perfil.values()) {
            garantirRole(existentes, perfil.codigo(), perfil.nome() + " - " + perfil.descricao());
        }
        // Recarrega pra ter o id das roles recem-criadas (a composicao exige id)
        existentes = porNome(keycloak.listarRoles());

        for (Perfil perfil : Perfil.values()) {
            sincronizarComposicao(perfil, existentes);
        }
        if (props.usuariosDev()) {
            for (Perfil perfil : Perfil.values()) {
                garantirUsuarioDev(perfil, existentes);
            }
        }
        log.info("Catalogo de acesso sincronizado com o Keycloak: {} permissoes, {} perfis",
                Permissao.values().length, Perfil.values().length);
    }

    private void garantirRole(Map<String, KcRole> existentes, String nome, String descricao) {
        KcRole atual = existentes.get(nome);
        if (atual == null) {
            keycloak.criarRole(nome, descricao);
            log.info("Role criada no Keycloak: {}", nome);
        } else if (!Objects.equals(atual.description(), descricao)) {
            keycloak.atualizarDescricaoRole(nome, descricao);
        }
    }

    private void sincronizarComposicao(Perfil perfil, Map<String, KcRole> existentes) {
        Set<String> desejadas = perfil.permissoes().stream().map(Permissao::codigo).collect(Collectors.toSet());
        List<KcRole> atuais = keycloak.compostasDaRole(perfil.codigo());
        Set<String> nomesAtuais = atuais.stream().map(KcRole::name).collect(Collectors.toSet());

        List<KcRole> faltando = desejadas.stream()
                .filter(nome -> !nomesAtuais.contains(nome))
                .map(existentes::get)
                .toList();
        List<KcRole> sobrando = atuais.stream()
                .filter(role -> !desejadas.contains(role.name()))
                .toList();

        keycloak.adicionarCompostas(perfil.codigo(), faltando);
        keycloak.removerCompostas(perfil.codigo(), sobrando);
        if (!faltando.isEmpty() || !sobrando.isEmpty()) {
            log.info("Perfil {} ajustado no Keycloak: +{} -{}", perfil.codigo(),
                    faltando.stream().map(KcRole::name).toList(), sobrando.stream().map(KcRole::name).toList());
        }
    }

    private void garantirUsuarioDev(Perfil perfil, Map<String, KcRole> existentes) {
        String login = perfil.usuarioDev();
        if (keycloak.buscarPorLogin(login).isPresent()) {
            return;
        }
        // Sobrenome so com letras: o validador de nome de pessoa do Keycloak 26 recusa
        // pontuacao como "(teste)" (error-person-name-invalid-character)
        String id = keycloak.criarUsuario(login, perfil.nome(), "Teste", login + "@datagis.local");
        keycloak.definirSenha(id, login, false);
        keycloak.adicionarRoles(id, List.of(existentes.get(perfil.codigo())));
        log.info("Usuario de teste criado no Keycloak: {} (perfil {})", login, perfil.codigo());
    }

    private static Map<String, KcRole> porNome(List<KcRole> roles) {
        return roles.stream().collect(Collectors.toMap(KcRole::name, Function.identity(), (a, b) -> a));
    }
}
