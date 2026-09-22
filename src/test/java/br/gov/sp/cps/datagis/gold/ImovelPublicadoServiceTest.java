package br.gov.sp.cps.datagis.gold;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImovelPublicadoServiceTest {

    @Mock
    private ImovelPublicadoRepository repository;

    @InjectMocks
    private ImovelPublicadoService service;

    @Test
    void listarTodos_DeveRetornarListaDeImoveis() {
        ImovelPublicado imovel = new ImovelPublicado();
        imovel.setCodImovel("PR-DEMO-123");
        when(repository.findAll()).thenReturn(List.of(imovel));

        List<ImovelPublicado> resultado = service.listarTodos();

        assertFalse(resultado.isEmpty());
        assertEquals("PR-DEMO-123", resultado.get(0).getCodImovel());
    }

    @Test
    void buscarPorCodImovel_DeveRetornarImovelQuandoExistir() {
        ImovelPublicado imovel = new ImovelPublicado();
        imovel.setCodImovel("PR-DEMO-456");
        imovel.setValorIsap(0.18);
        when(repository.findByCodImovel("PR-DEMO-456")).thenReturn(Optional.of(imovel));

        ImovelPublicado resultado = service.buscarPorCodImovel("PR-DEMO-456");

        assertNotNull(resultado);
        assertEquals(0.18, resultado.getValorIsap());
    }

    @Test
    void buscarPorCodImovel_DeveLancarExcecaoQuandoNaoExistir() {
        when(repository.findByCodImovel("INEXISTENTE")).thenReturn(Optional.empty());

        Exception exception = assertThrows(RuntimeException.class, () -> {
            service.buscarPorCodImovel("INEXISTENTE");
        });

        assertEquals("Imóvel não encontrado com o código: INEXISTENTE", exception.getMessage());
    }
}