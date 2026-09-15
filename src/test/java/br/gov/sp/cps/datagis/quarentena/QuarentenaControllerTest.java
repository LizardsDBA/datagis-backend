package br.gov.sp.cps.datagis.quarentena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class QuarentenaControllerTest {

    @Mock
    private QuarentenaService quarentenaService;

    @InjectMocks
    private QuarentenaController controller;

    @Test
    @DisplayName("Deve listar itens da quarentena com sucesso")
    void deveListarItens() {
        ItemQuarentenaDto item = new ItemQuarentenaDto(
                1L, "imovel_rural", "PR-4106902-DEMO", 1L, "CAR PR", 10L, 100L,
                "CPF_CNPJ_INVALIDO", "CPF ou CNPJ inválido", "cpf", "111.111",
                "Dígitos inválidos", "PENDENTE", "Pendente de análise",
                "2026-09-13 12:00:00", null, "{\"cpf\":\"111.111\"}"
        );
        when(quarentenaService.listarItens(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(item));

        ResponseEntity<List<ItemQuarentenaDto>> response = controller.listar(null, null, null, null, null, 0, 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).chaveIdentificacao()).isEqualTo("PR-4106902-DEMO");
    }

    @Test
    @DisplayName("Deve retornar metricas agregadas da quarentena")
    void deveObterMetricas() {
        MetricasQuarentenaDto metricas = new MetricasQuarentenaDto(
                10L, 5L, 2L, 2L, 1L,
                Map.of("GEOMETRIA_INVALIDA", 4L, "CPF_CNPJ_INVALIDO", 6L),
                Map.of("imovel_rural", 8L, "embargo_ambiental", 2L)
        );
        when(quarentenaService.obterMetricas()).thenReturn(metricas);

        ResponseEntity<MetricasQuarentenaDto> response = controller.obterMetricas();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().totalRegistros()).isEqualTo(10L);
        assertThat(response.getBody().totalPendentes()).isEqualTo(5L);
        assertThat(response.getBody().totalPorTipoErro()).containsEntry("GEOMETRIA_INVALIDA", 4L);
    }

    @Test
    @DisplayName("Deve buscar item por entidade e id")
    void deveBuscarPorId() {
        ItemQuarentenaDto item = new ItemQuarentenaDto(
                5L, "embargo_ambiental", "TAD-99", 2L, "IBAMA Embargos", 11L, 200L,
                "VALOR_FORA_DO_DOMINIO", "Fora do domínio", "tipo_embargo", "XPTO",
                "Domínio inexistente", "PENDENTE", "Pendente",
                "2026-09-13 12:00:00", null, "{}"
        );
        when(quarentenaService.buscarPorId("embargo_ambiental", 5L)).thenReturn(item);

        ResponseEntity<ItemQuarentenaDto> response = controller.buscarPorId("embargo_ambiental", 5L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().tipoErroCodigo()).isEqualTo("VALOR_FORA_DO_DOMINIO");
    }

    @Test
    @DisplayName("Deve retornar 404 ao buscar item inexistente")
    void deveRetornar404QuandoNaoEncontrado() {
        when(quarentenaService.buscarPorId("imovel_rural", 999L)).thenReturn(null);

        ResponseEntity<ItemQuarentenaDto> response = controller.buscarPorId("imovel_rural", 999L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Deve descartar item com justificativa")
    void deveDescartarItem() {
        when(quarentenaService.descartar(eq("imovel_rural"), eq(1L), any())).thenReturn(true);

        AcaoQuarentenaRequest req = new AcaoQuarentenaRequest("Dado irrecuperável", null, 42L);
        ResponseEntity<Void> response = controller.descartar("imovel_rural", 1L, req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(quarentenaService).descartar(eq("imovel_rural"), eq(1L), eq(req));
    }

    @Test
    @DisplayName("Deve reprocessar item corrigido")
    void deveReprocessarItem() {
        when(quarentenaService.marcarCorrigido(eq("imovel_rural"), eq(1L), any())).thenReturn(true);

        AcaoQuarentenaRequest req = new AcaoQuarentenaRequest("Corrigido", "{\"cod_imovel\":\"PR-001\"}", 42L);
        ResponseEntity<Void> response = controller.reprocessar("imovel_rural", 1L, req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(quarentenaService).marcarCorrigido(eq("imovel_rural"), eq(1L), eq(42L));
    }

    @Test
    @DisplayName("Deve liberar lote de quarentena com sucesso")
    void deveLiberarLote() {
        when(quarentenaService.liberarLote(eq(100L), any())).thenReturn(true);

        AcaoQuarentenaRequest req = new AcaoQuarentenaRequest(null, null, 1L);
        ResponseEntity<Void> response = controller.liberarLote(100L, req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(quarentenaService).liberarLote(eq(100L), eq(1L));
    }

    @Test
    @DisplayName("Deve descartar lote de quarentena com sucesso")
    void deveDescartarLote() {
        when(quarentenaService.descartarLote(eq(100L), any(), any())).thenReturn(true);

        AcaoQuarentenaRequest req = new AcaoQuarentenaRequest("Lote inconsistente", null, 1L);
        ResponseEntity<Void> response = controller.descartarLote(100L, req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(quarentenaService).descartarLote(eq(100L), eq("Lote inconsistente"), eq(1L));
    }
}
