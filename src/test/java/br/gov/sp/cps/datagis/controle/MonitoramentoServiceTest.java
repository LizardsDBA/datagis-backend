package br.gov.sp.cps.datagis.controle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.controle.monitoramento.MonitoramentoGlobalResponse;
import br.gov.sp.cps.datagis.gold.GoldPublicacaoService;
import br.gov.sp.cps.datagis.ingestao.FaseExecucao;
import br.gov.sp.cps.datagis.ingestao.ProgressoExecucao;
import br.gov.sp.cps.datagis.ingestao.ProgressoExecucaoRegistry;
import br.gov.sp.cps.datagis.validacao.ProgressoTransicao;
import br.gov.sp.cps.datagis.validacao.ProgressoTransicaoRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MonitoramentoServiceTest {

    @Mock
    private ProgressoExecucaoRegistry progressoExecucaoRegistry;

    @Mock
    private ProgressoTransicaoRegistry progressoTransicaoRegistry;

    @Mock
    private GoldPublicacaoService goldPublicacaoService;

    @Mock
    private FonteDadoRepository fonteDadoRepository;

    @Mock
    private LoteIngestaoRepository loteIngestaoRepository;

    @InjectMocks
    private MonitoramentoService monitoramentoService;

    @Test
    @DisplayName("Deve retornar status global vazio quando nenhum registry contiver tarefas")
    void deveRetornarStatusVazioQuandoOcioso() {
        when(progressoExecucaoRegistry.obterTodos()).thenReturn(Map.of());
        when(progressoTransicaoRegistry.obterTodos()).thenReturn(Map.of());
        when(goldPublicacaoService.obterStatusConsolidado()).thenReturn(null);

        MonitoramentoGlobalResponse resp = monitoramentoService.obterStatusGlobal();

        assertThat(resp).isNotNull();
        assertThat(resp.totalEmAndamento()).isEqualTo(0);
        assertThat(resp.ingestoes()).isEmpty();
        assertThat(resp.migracoesSilver()).isEmpty();
        assertThat(resp.migracaoGold()).isNull();
    }

    @Test
    @DisplayName("Deve calcular corretamente tarefas ativas em todas as esteiras")
    void deveCalcularTarefasAtivasEmTodasAsEsteiras() {
        // Ingestão ativa
        ProgressoExecucao progIngestao = new ProgressoExecucao(
                FaseExecucao.LENDO_REGISTROS, 500, 1000L, "Processando linhas", LocalDateTime.now());
        when(progressoExecucaoRegistry.obterTodos()).thenReturn(Map.of(1L, progIngestao));

        FonteDado fonte = new FonteDado();
        fonte.setId(1L);
        fonte.setNome("CAR PR");
        when(fonteDadoRepository.findById(1L)).thenReturn(Optional.of(fonte));

        // Transição Silver ativa
        ProgressoTransicao progTransicao = new ProgressoTransicao(
                42L, "VALIDANDO_REGRAS", 300, 1000, 0, "Validando geometrias", "EM_PROCESSAMENTO", LocalDateTime.now(), null);
        when(progressoTransicaoRegistry.obterTodos()).thenReturn(Map.of(42L, progTransicao));
        when(loteIngestaoRepository.findByIdComFonte(42L)).thenReturn(Optional.empty());

        // Gold ativa
        GoldPublicacaoService.StatusPublicacaoIndicadores statusInd = new GoldPublicacaoService.StatusPublicacaoIndicadores(
                1L, "EM_ANDAMENTO", 1000L, 500L, 500L, 0L, null, LocalDateTime.now(), null);
        GoldPublicacaoService.StatusGoldConsolidado statusGold = new GoldPublicacaoService.StatusGoldConsolidado(
                true, statusInd, List.of());
        when(goldPublicacaoService.obterStatusConsolidado()).thenReturn(statusGold);

        MonitoramentoGlobalResponse resp = monitoramentoService.obterStatusGlobal();

        assertThat(resp).isNotNull();
        // 1 na ingestão + 1 na Silver + 1 na Gold = 3
        assertThat(resp.totalEmAndamento()).isEqualTo(3);

        assertThat(resp.ingestoes()).hasSize(1);
        assertThat(resp.ingestoes().get(0).fonteNome()).isEqualTo("CAR PR");
        assertThat(resp.ingestoes().get(0).percentual()).isEqualTo(50);
        assertThat(resp.ingestoes().get(0).emExecucao()).isTrue();

        assertThat(resp.migracoesSilver()).hasSize(1);
        assertThat(resp.migracoesSilver().get(0).loteId()).isEqualTo(42L);
        assertThat(resp.migracoesSilver().get(0).percentual()).isEqualTo(30);
        assertThat(resp.migracoesSilver().get(0).emExecucao()).isTrue();

        assertThat(resp.migracaoGold()).isNotNull();
        assertThat(resp.migracaoGold().emExecucao()).isTrue();
        assertThat(resp.migracaoGold().percentualGeral()).isEqualTo(50);
    }
}
