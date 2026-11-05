package br.gov.sp.cps.datagis.favorito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.favorito.FavoritoController.FavoritosRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class FavoritoControllerTest {

    private final FavoritoRepositorio repositorio = mock(FavoritoRepositorio.class);
    private final FavoritoController controller = new FavoritoController(repositorio);

    private static Jwt usuario(String id) {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(60), java.util.Map.of("alg", "none"),
                java.util.Map.of("sub", id));
    }

    @Test
    void listaOsFavoritosDoUsuarioDoToken() {
        when(repositorio.listar("uuid-maria")).thenReturn(List.of("/fontes", "/lotes"));

        assertThat(controller.listar(usuario("uuid-maria")).rotas()).containsExactly("/fontes", "/lotes");
    }

    @Test
    void salvaParaOUsuarioDoTokenNaOrdemRecebida() {
        var resposta = controller.substituir(usuario("uuid-maria"), new FavoritosRequest(List.of("/lotes", "/fontes")));

        verify(repositorio).substituir("uuid-maria", List.of("/lotes", "/fontes"));
        assertThat(resposta.rotas()).containsExactly("/lotes", "/fontes");
    }

    @Test
    void listaVaziaApagaTodosOsFavoritos() {
        controller.substituir(usuario("uuid-maria"), new FavoritosRequest(List.of()));

        verify(repositorio).substituir("uuid-maria", List.of());
    }

    @Test
    void aceitaRotaComSubpasta() {
        assertThat(FavoritoController.validar(List.of("/admin/controle-acesso"))).containsExactly("/admin/controle-acesso");
    }

    @Test
    void recusaSemLista() {
        assertThatThrownBy(() -> controller.substituir(usuario("u"), new FavoritosRequest(null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.substituir(usuario("u"), null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repositorio, never()).substituir(anyString(), any());
    }

    @Test
    void recusaRotaQueNaoEhCaminhoDeTela() {
        for (String invalida : Arrays.asList("fontes", "https://outro.site", "/fontes?x=1", "/Fontes", "/a b", "", null)) {
            List<String> lista = new ArrayList<>();
            lista.add(invalida);
            assertThatThrownBy(() -> FavoritoController.validar(lista))
                    .as("rota %s", invalida)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void recusaRotaRepetida() {
        assertThatThrownBy(() -> FavoritoController.validar(List.of("/fontes", "/fontes")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repetida");
    }

    @Test
    void recusaMaisQueOMaximo() {
        List<String> demais = IntStream.rangeClosed(0, FavoritoController.MAXIMO).mapToObj(i -> "/tela" + i).toList();

        assertThatThrownBy(() -> FavoritoController.validar(demais))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(FavoritoController.MAXIMO));
    }
}
