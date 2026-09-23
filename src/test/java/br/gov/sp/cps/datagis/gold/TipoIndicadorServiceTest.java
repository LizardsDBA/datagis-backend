package br.gov.sp.cps.datagis.gold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TipoIndicadorServiceTest {

    @Mock
    private TipoIndicadorRepository repository;

    @InjectMocks
    private TipoIndicadorService service;

    @Test
    @DisplayName("Deve cadastrar tipo de indicador válido com sucesso")
    void deveCadastrarTipoValido() {
        TipoIndicadorRequest request = new TipoIndicadorRequest(
                "isap",
                "  Índice de Sobreposição com Áreas Protegidas  ",
                true,
                true
        );

        when(repository.existsByCodigo("ISAP")).thenReturn(false);
        when(repository.save(any(TipoIndicador.class))).thenAnswer(invocation -> {
            TipoIndicador salvo = invocation.getArgument(0);
            salvo.setId(1L);
            return salvo;
        });

        TipoIndicador resultado = service.cadastrar(request);

        assertThat(resultado.getId()).isEqualTo(1L);
        assertThat(resultado.getCodigo()).isEqualTo("ISAP");
        assertThat(resultado.getDescricao()).isEqualTo("Índice de Sobreposição com Áreas Protegidas");
        assertThat(resultado.getTemFonte()).isTrue();
        assertThat(resultado.getAtivo()).isTrue();

        ArgumentCaptor<TipoIndicador> captor = ArgumentCaptor.forClass(TipoIndicador.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getCodigo()).isEqualTo("ISAP");
        assertThat(captor.getValue().getDescricao()).isEqualTo("Índice de Sobreposição com Áreas Protegidas");
    }

    @Test
    @DisplayName("Deve recusar cadastro quando dados forem nulos")
    void deveRecusarCadastroComDadosNulos() {
        assertThatThrownBy(() -> service.cadastrar(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Dados do tipo de indicador não podem ser nulos");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Deve recusar código nulo ou vazio no cadastro")
    void deveRecusarCodigoVazioNoCadastro() {
        TipoIndicadorRequest requestVazio = new TipoIndicadorRequest("", "Descrição", true, true);
        TipoIndicadorRequest requestNulo = new TipoIndicadorRequest(null, "Descrição", true, true);

        assertThatThrownBy(() -> service.cadastrar(requestVazio))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Código do tipo de indicador é obrigatório");

        assertThatThrownBy(() -> service.cadastrar(requestNulo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Código do tipo de indicador é obrigatório");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Deve recusar código com mais de 20 caracteres")
    void deveRecusarCodigoMaiorQue20Caracteres() {
        String codigoLongo = "A".repeat(21);
        TipoIndicadorRequest request = new TipoIndicadorRequest(codigoLongo, "Descrição", true, true);

        assertThatThrownBy(() -> service.cadastrar(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Código não pode ter mais de 20 caracteres");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Deve converter código para uppercase e remover espaços")
    void deveConverterCodigoParaUppercase() {
        TipoIndicadorRequest request = new TipoIndicadorRequest("  iae  ", "Índice de Área Embargada", true, true);

        when(repository.existsByCodigo("IAE")).thenReturn(false);
        when(repository.save(any(TipoIndicador.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TipoIndicador resultado = service.cadastrar(request);

        assertThat(resultado.getCodigo()).isEqualTo("IAE");
        verify(repository).existsByCodigo("IAE");
    }

    @Test
    @DisplayName("Deve recusar descrição vazia ou com mais de 200 caracteres no cadastro")
    void deveRecusarDescricaoInvalidaNoCadastro() {
        TipoIndicadorRequest requestDescricaoVazia = new TipoIndicadorRequest("IDESMAT", "   ", true, true);
        TipoIndicadorRequest requestDescricaoLonga = new TipoIndicadorRequest("IDESMAT", "D".repeat(201), true, true);

        assertThatThrownBy(() -> service.cadastrar(requestDescricaoVazia))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Descrição do tipo de indicador é obrigatória");

        assertThatThrownBy(() -> service.cadastrar(requestDescricaoLonga))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Descrição não pode ter mais de 200 caracteres");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Deve recusar código duplicado")
    void deveRecusarCodigoDuplicado() {
        TipoIndicadorRequest request = new TipoIndicadorRequest("ISAP", "Descrição", true, true);

        when(repository.existsByCodigo("ISAP")).thenReturn(true);

        assertThatThrownBy(() -> service.cadastrar(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Já existe um tipo de indicador com o código ISAP");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Deve listar todos os tipos de indicadores")
    void deveListarTodosOsTipos() {
        List<TipoIndicador> lista = List.of(
                TipoIndicador.builder().id(1L).codigo("ISAP").descricao("Protegidas").build(),
                TipoIndicador.builder().id(2L).codigo("IAE").descricao("Embargos").build()
        );

        when(repository.findAll()).thenReturn(lista);

        List<TipoIndicador> resultado = service.listar();

        assertThat(resultado).hasSize(2);
        assertThat(resultado.get(0).getCodigo()).isEqualTo("ISAP");
        assertThat(resultado.get(1).getCodigo()).isEqualTo("IAE");
    }

    @Test
    @DisplayName("Deve buscar tipo de indicador por ID com sucesso")
    void deveBuscarPorIdComSucesso() {
        TipoIndicador tipo = TipoIndicador.builder().id(5L).codigo("IFC").descricao("Focos").build();
        when(repository.findById(5L)).thenReturn(Optional.of(tipo));

        TipoIndicador resultado = service.buscarPorId(5L);

        assertThat(resultado).isNotNull();
        assertThat(resultado.getId()).isEqualTo(5L);
        assertThat(resultado.getCodigo()).isEqualTo("IFC");
    }

    @Test
    @DisplayName("Deve lançar exceção ao buscar ID inexistente")
    void deveFalharAoBuscarIdInexistente() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.buscarPorId(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tipo de indicador com ID 99 não encontrado");
    }

    @Test
    @DisplayName("Deve atualizar descrição, temFonte e ativo, mantendo o código imutável")
    void deveProibirAlteracaoDoCodigoEAtualizarOutrosCampos() {
        TipoIndicador existente = TipoIndicador.builder()
                .id(3L)
                .codigo("IDESMAT")
                .descricao("Descrição Antiga")
                .temFonte(true)
                .ativo(true)
                .dataAdd(LocalDateTime.of(2026, 1, 1, 10, 0))
                .build();

        when(repository.findById(3L)).thenReturn(Optional.of(existente));
        when(repository.save(any(TipoIndicador.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TipoIndicadorRequest updateRequest = new TipoIndicadorRequest(
                "TENTATIVA_MUDAR_CODIGO",
                "  Nova Descrição Atualizada  ",
                false,
                false
        );

        TipoIndicador atualizado = service.atualizar(3L, updateRequest);

        assertThat(atualizado.getId()).isEqualTo(3L);
        assertThat(atualizado.getCodigo()).isEqualTo("IDESMAT"); // Permanece inalterado
        assertThat(atualizado.getDescricao()).isEqualTo("Nova Descrição Atualizada");
        assertThat(atualizado.getTemFonte()).isFalse();
        assertThat(atualizado.getAtivo()).isFalse();
        assertThat(atualizado.getDataAdd()).isEqualTo(LocalDateTime.of(2026, 1, 1, 10, 0));

        verify(repository).save(existente);
    }

    @Test
    @DisplayName("Deve recusar atualização com dados nulos")
    void deveRecusarAtualizacaoComDadosNulos() {
        assertThatThrownBy(() -> service.atualizar(1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Dados para atualização não podem ser nulos");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Deve desativar tipo via soft-delete e nunca invocar deleteById")
    void deveDesativarEmVezDeDeletar() {
        TipoIndicador tipo = TipoIndicador.builder()
                .id(7L)
                .codigo("IAPP")
                .descricao("Preservação Permanente")
                .ativo(true)
                .build();

        when(repository.findById(7L)).thenReturn(Optional.of(tipo));
        when(repository.save(any(TipoIndicador.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TipoIndicador resultado = service.desativar(7L);

        assertThat(resultado.getAtivo()).isFalse();

        verify(repository).save(tipo);
        verify(repository, never()).delete(any());
        verify(repository, never()).deleteById(any());
    }
}
