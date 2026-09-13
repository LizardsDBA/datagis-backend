package br.gov.sp.cps.datagis.ingestao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ProgressoExecucaoResponseTest {

    @Test
    void deveCalcularPercentualQuandoTotalConhecido() {
        ProgressoExecucao progresso = new ProgressoExecucao(FaseExecucao.GRAVANDO, 250, 1000L, null, LocalDateTime.now());

        ProgressoExecucaoResponse resposta = ProgressoExecucaoResponse.de(progresso);

        assertThat(resposta.percentual()).isEqualTo(25);
    }

    @Test
    void percentualDeveVirNuloQuandoTotalDesconhecido() {
        ProgressoExecucao progresso = new ProgressoExecucao(FaseExecucao.BUSCANDO_DADOS, 4000, null, "página 4", LocalDateTime.now());

        ProgressoExecucaoResponse resposta = ProgressoExecucaoResponse.de(progresso);

        assertThat(resposta.percentual()).isNull();
        assertThat(resposta.registrosProcessados()).isEqualTo(4000);
        assertThat(resposta.detalhe()).isEqualTo("página 4");
    }

    @Test
    void percentualNaoDevePassarDe100MesmoSeProcessadosExcederemOTotal() {
        // Pode acontecer no meio de um gravarStreaming "sem mudança": grava tudo antes
        // de saber que ia apagar - nao deve mostrar 100%+ nesse instante intermediario.
        ProgressoExecucao progresso = new ProgressoExecucao(FaseExecucao.GRAVANDO, 105, 100L, null, LocalDateTime.now());

        ProgressoExecucaoResponse resposta = ProgressoExecucaoResponse.de(progresso);

        assertThat(resposta.percentual()).isEqualTo(100);
    }

    @Test
    void ociosoNaoTemFase() {
        ProgressoExecucaoResponse resposta = ProgressoExecucaoResponse.ocioso();

        assertThat(resposta.fase()).isNull();
        assertThat(resposta.registrosProcessados()).isZero();
        assertThat(resposta.percentual()).isNull();
    }
}
