package br.gov.sp.cps.datagis.controle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.controle.monitoramento.MonitoramentoGlobalResponse;
import br.gov.sp.cps.datagis.controle.monitoramento.MonitoramentoIngestaoItem;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class MonitoramentoControllerTest {

    @Mock
    private MonitoramentoService monitoramentoService;

    @InjectMocks
    private MonitoramentoController controller;

    @Test
    @DisplayName("Deve retornar status global de monitoramento com sucesso")
    void deveRetornarStatusGlobal() {
        MonitoramentoIngestaoItem item = new MonitoramentoIngestaoItem(
                1L, "CAR PR", "LENDO_REGISTROS", 50, 100L, 50, "Carregando", true);
        MonitoramentoGlobalResponse mockResp = new MonitoramentoGlobalResponse(
                1, List.of(item), List.of(), null);
        when(monitoramentoService.obterStatusGlobal()).thenReturn(mockResp);

        ResponseEntity<MonitoramentoGlobalResponse> response = controller.statusGlobal();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().totalEmAndamento()).isEqualTo(1);
        assertThat(response.getBody().ingestoes()).hasSize(1);
    }
}
