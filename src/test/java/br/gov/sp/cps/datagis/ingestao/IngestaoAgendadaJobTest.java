package br.gov.sp.cps.datagis.ingestao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.SchedulerException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * LIZARDS-54: uma execução por fonte de cada vez e próxima execução sempre em dia.
 * O Quartz injeta os campos por reflexão (AutowiringSpringBeanJobFactory), então o
 * teste faz o mesmo.
 */
@ExtendWith(MockitoExtension.class)
class IngestaoAgendadaJobTest {

    @Mock
    private IngestaoExecucaoService ingestaoExecucaoService;

    @Mock
    private AgendamentoFonteService agendamentoFonteService;

    @Mock
    private JobExecutionContext contexto;

    private final ProgressoExecucaoRegistry progresso = new ProgressoExecucaoRegistry();
    private IngestaoAgendadaJob job;

    @BeforeEach
    void montarJob() {
        job = new IngestaoAgendadaJob();
        ReflectionTestUtils.setField(job, "ingestaoExecucaoService", ingestaoExecucaoService);
        ReflectionTestUtils.setField(job, "progressoRegistry", progresso);
        ReflectionTestUtils.setField(job, "agendamentoFonteService", agendamentoFonteService);

        JobDataMap dados = new JobDataMap();
        dados.put("fonteId", 10L);
        // lenient: o último teste só olha a anotação da classe e não chama o job
        lenient().when(contexto.getMergedJobDataMap()).thenReturn(dados);
    }

    @Test
    void deveExecutarAIngestaoEGravarAProximaExecucao() throws JobExecutionException, SchedulerException {
        job.execute(contexto);

        verify(ingestaoExecucaoService).executar(10L);
        verify(agendamentoFonteService).atualizarProximaExecucaoDoTrigger(10L);
    }

    @Test
    void devePularQuandoAMesmaFonteJaEstaRodandoPorOutroCaminho() throws JobExecutionException, SchedulerException {
        progresso.iniciar(10L); // ex.: execução manual pelo botão da tela

        job.execute(contexto);

        verify(ingestaoExecucaoService, never()).executar(10L);
        verify(agendamentoFonteService).atualizarProximaExecucaoDoTrigger(10L);
    }

    @Test
    void deveGravarAProximaExecucaoMesmoQuandoAIngestaoFalha() throws SchedulerException {
        when(ingestaoExecucaoService.executar(10L)).thenThrow(new IllegalStateException("servidor fora do ar"));

        assertThatThrownBy(() -> job.execute(contexto)).isInstanceOf(JobExecutionException.class);

        verify(agendamentoFonteService).atualizarProximaExecucaoDoTrigger(10L);
    }

    @Test
    void falhaAoGravarAProximaExecucaoNaoEscondeOErroDaIngestao() throws SchedulerException {
        IllegalStateException erroDaIngestao = new IllegalStateException("servidor fora do ar");
        when(ingestaoExecucaoService.executar(10L)).thenThrow(erroDaIngestao);
        doThrow(new SchedulerException("banco fora do ar"))
                .when(agendamentoFonteService).atualizarProximaExecucaoDoTrigger(10L);

        assertThatThrownBy(() -> job.execute(contexto))
                .isInstanceOf(JobExecutionException.class)
                .hasCause(erroDaIngestao);
    }

    @Test
    void naoDeveRodarDuasVezesAoMesmoTempo() {
        assertThat(IngestaoAgendadaJob.class.isAnnotationPresent(DisallowConcurrentExecution.class)).isTrue();
    }
}
