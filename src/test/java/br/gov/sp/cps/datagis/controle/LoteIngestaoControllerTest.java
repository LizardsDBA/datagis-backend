package br.gov.sp.cps.datagis.controle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.config.OperacaoEmAndamentoException;
import br.gov.sp.cps.datagis.validacao.ProgressoTransicao;
import br.gov.sp.cps.datagis.validacao.ProgressoTransicaoRegistry;
import br.gov.sp.cps.datagis.validacao.ProgressoTransicaoResponse;
import br.gov.sp.cps.datagis.validacao.TransicaoBronzeSilverService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class LoteIngestaoControllerTest {

    @Mock
    private LoteIngestaoRepository loteIngestaoRepository;

    @Mock
    private TransicaoBronzeSilverService transicaoBronzeSilverService;

    @Mock
    private ProgressoTransicaoRegistry progressoTransicaoRegistry;

    @InjectMocks
    private LoteIngestaoController controller;

    @Test
    @DisplayName("Deve retornar progresso ocioso quando lote nao estiver em execucao")
    void deveRetornarProgressoOcioso() {
        when(progressoTransicaoRegistry.obter(42L)).thenReturn(Optional.empty());

        ResponseEntity<ProgressoTransicaoResponse> response = controller.progresso(42L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().loteId()).isEqualTo(42L);
        assertThat(response.getBody().fase()).isEqualTo("OCIOSO");
        assertThat(response.getBody().percentual()).isEqualTo(0);
    }

    @Test
    @DisplayName("Deve retornar progresso em andamento quando lote estiver executando")
    void deveRetornarProgressoEmAndamento() {
        ProgressoTransicao progresso = new ProgressoTransicao(
                42L, "VALIDANDO_REGRAS", 50, 100, 0, "Validando geometrias", "EM_PROCESSAMENTO", LocalDateTime.now(), null);
        when(progressoTransicaoRegistry.obter(42L)).thenReturn(Optional.of(progresso));

        ResponseEntity<ProgressoTransicaoResponse> response = controller.progresso(42L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().percentual()).isEqualTo(50);
        assertThat(response.getBody().fase()).isEqualTo("VALIDANDO_REGRAS");
        assertThat(response.getBody().detalhe()).isEqualTo("Validando geometrias");
    }

    @Test
    @DisplayName("Deve reprocessar lote assincronamente e retornar 202 Accepted")
    void deveReprocessarLoteAssincronamente() {
        when(progressoTransicaoRegistry.isExecutando(42L)).thenReturn(false);

        ResponseEntity<ProgressoTransicaoResponse> response = controller.reprocessar(42L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(progressoTransicaoRegistry).iniciar(eq(42L), eq(0), eq("INICIANDO"));
        verify(transicaoBronzeSilverService).processarLoteAssincrono(eq(42L));
    }

    @Test
    @DisplayName("Deve lancar OperacaoEmAndamentoException ao reprocessar lote ja em andamento")
    void deveLancarConflitoAoReprocessarLoteEmAndamento() {
        when(progressoTransicaoRegistry.isExecutando(42L)).thenReturn(true);

        assertThatThrownBy(() -> controller.reprocessar(42L))
                .isInstanceOf(OperacaoEmAndamentoException.class)
                .hasMessageContaining("O lote #42 já está sendo reprocessado");
    }
}
