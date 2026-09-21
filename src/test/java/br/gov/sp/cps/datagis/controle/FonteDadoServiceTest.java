package br.gov.sp.cps.datagis.controle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FonteDadoServiceTest {

    @Mock
    private FonteDadoRepository repository;

    @Test
    void deveCadastrarComTipoDeAquisicaoValido() {
        FonteDadoService service = new FonteDadoService(repository);
        when(repository.save(any(FonteDado.class))).thenAnswer(chamada -> chamada.getArgument(0));

        FonteDadoRequest dados = new FonteDadoRequest(
                "IBAMA - Embargos", "IBAMA", "UPLOAD_MANUAL", null, null, "CSV", null, null, null, null);

        FonteDado fonte = service.cadastrar(dados);

        assertThat(fonte.getNome()).isEqualTo("IBAMA - Embargos");
        assertThat(fonte.getAtivo()).isTrue(); // nao informado no request, entra como true
        assertThat(fonte.getSeparadorCsv()).isEqualTo(","); // nao informado, entra como virgula
    }

    @Test
    void deveRecusarTipoDeAquisicaoDesconhecido() {
        FonteDadoService service = new FonteDadoService(repository);
        FonteDadoRequest dados = new FonteDadoRequest(
                "Teste", null, "QUALQUER_COISA", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.cadastrar(dados))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deveAceitarSeparadoresCsvSuportados() {
        FonteDadoService service = new FonteDadoService(repository);
        when(repository.save(any(FonteDado.class))).thenAnswer(chamada -> chamada.getArgument(0));

        FonteDadoRequest dados = new FonteDadoRequest(
                "INPE Queimadas", "INPE", "UPLOAD_MANUAL", null, null, "CSV", null, null, ";", null);

        FonteDado fonte = service.cadastrar(dados);

        assertThat(fonte.getSeparadorCsv()).isEqualTo(";");
    }

    @Test
    void deveRecusarSeparadorCsvDesconhecido() {
        FonteDadoService service = new FonteDadoService(repository);
        FonteDadoRequest dados = new FonteDadoRequest(
                "Teste", null, "UPLOAD_MANUAL", null, null, "CSV", null, null, "@", null);

        assertThatThrownBy(() -> service.cadastrar(dados))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listarDelegaPraORepositorio() {
        FonteDadoService service = new FonteDadoService(repository);
        FonteDado fonte = FonteDado.builder().id(1L).nome("CAR").build();
        when(repository.findAll()).thenReturn(List.of(fonte));

        assertThat(service.listar()).containsExactly(fonte);
    }

    @Test
    void deveAtualizarUmaFonteExistente() {
        FonteDadoService service = new FonteDadoService(repository);
        FonteDado existente = FonteDado.builder().id(1L).nome("Antigo").tipoAquisicao("UPLOAD_MANUAL").build();
        when(repository.findById(1L)).thenReturn(java.util.Optional.of(existente));
        when(repository.save(any(FonteDado.class))).thenAnswer(chamada -> chamada.getArgument(0));

        FonteDadoRequest dados = new FonteDadoRequest(
                "Novo nome", "Órgão", "API_REST", "https://x", "0 0 3 * * ?", "GEOJSON", null, null, null, false);

        FonteDado atualizado = service.atualizar(1L, dados);

        assertThat(atualizado.getNome()).isEqualTo("Novo nome");
        assertThat(atualizado.getTipoAquisicao()).isEqualTo("API_REST");
        assertThat(atualizado.getAtivo()).isFalse();
    }

    @Test
    void deveFalharAoBuscarFonteInexistente() {
        FonteDadoService service = new FonteDadoService(repository);
        when(repository.findById(99L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.buscarPorId(99L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
