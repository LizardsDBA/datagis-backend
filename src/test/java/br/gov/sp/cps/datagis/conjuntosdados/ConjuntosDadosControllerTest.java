package br.gov.sp.cps.datagis.conjuntosdados;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class ConjuntosDadosControllerTest {

    @Mock
    private ConjuntosDadosService service;

    @InjectMocks
    private ConjuntosDadosController controller;

    @Test
    void deveBuscarUltimoLote() {
        LoteResumoDto resumo = new LoteResumoDto(7L, 7L, "IBGE - Malha Municipal (PR)", "municipio", 3, "PROMOVIDO_SILVER", LocalDateTime.now());
        when(service.buscarUltimoLotePromovido(7L)).thenReturn(resumo);

        ResponseEntity<LoteResumoDto> resposta = controller.buscarUltimoLote(7L);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getBody().loteId()).isEqualTo(7L);
    }

    @Test
    void deveListarBronzePaginado() {
        RegistroCamadaDto registro = new RegistroCamadaDto(1L, true, "POINT (0 0)", Map.of("NM_MUN", "Curitiba"));
        PaginaDto<RegistroCamadaDto> pagina = new PaginaDto<>(List.of(registro), 1, 0, 25);
        when(service.listarBronze(7L, 0, 25)).thenReturn(pagina);

        ResponseEntity<PaginaDto<RegistroCamadaDto>> resposta = controller.listarBronze(7L, 0, 25);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getBody().total()).isEqualTo(1);
        assertThat(resposta.getBody().itens()).hasSize(1);
    }

    @Test
    void deveListarSilverPaginado() {
        RegistroCamadaDto registro = new RegistroCamadaDto(1L, true, "POINT (0 0)", Map.of("NOME", "Curitiba"));
        PaginaDto<RegistroCamadaDto> pagina = new PaginaDto<>(List.of(registro), 1, 0, 25);
        when(service.listarSilver(7L, 0, 25)).thenReturn(pagina);

        ResponseEntity<PaginaDto<RegistroCamadaDto>> resposta = controller.listarSilver(7L, 0, 25);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getBody().itens().get(0).dados()).containsEntry("NOME", "Curitiba");
    }

    @Test
    void deveBuscarRegistroBronze() {
        RegistroCamadaDto registro = new RegistroCamadaDto(1L, false, null, Map.of("CD_MUN", "4106902"));
        when(service.buscarRegistroBronze(1L)).thenReturn(registro);

        ResponseEntity<RegistroCamadaDto> resposta = controller.buscarRegistroBronze(1L);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getBody().dados()).containsEntry("CD_MUN", "4106902");
    }

    @Test
    void deveRetornar200ComRegistroQuandoExisteNaSilver() {
        RegistroCamadaDto registro = new RegistroCamadaDto(1L, true, "POINT (0 0)", Map.of("NOME", "Curitiba"));
        when(service.buscarRegistroSilver(1L)).thenReturn(registro);

        ResponseEntity<RegistroCamadaDto> resposta = controller.buscarRegistroSilver(1L);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void deveRetornar204QuandoRegistroNuncaChegouNaSilver() {
        when(service.buscarRegistroSilver(38L)).thenReturn(null);

        ResponseEntity<RegistroCamadaDto> resposta = controller.buscarRegistroSilver(38L);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
