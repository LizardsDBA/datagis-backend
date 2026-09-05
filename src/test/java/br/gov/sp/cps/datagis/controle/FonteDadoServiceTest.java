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
                "IBAMA - Embargos", "IBAMA", "UPLOAD_MANUAL", null, null, "CSV", null);

        FonteDado fonte = service.cadastrar(dados);

        assertThat(fonte.getNome()).isEqualTo("IBAMA - Embargos");
        assertThat(fonte.getAtivo()).isTrue(); // nao informado no request, entra como true
    }

    @Test
    void deveRecusarTipoDeAquisicaoDesconhecido() {
        FonteDadoService service = new FonteDadoService(repository);
        FonteDadoRequest dados = new FonteDadoRequest(
                "Teste", null, "QUALQUER_COISA", null, null, null, null);

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
}
