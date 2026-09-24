package br.gov.sp.cps.datagis.ingestao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.FonteDadoRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.springframework.boot.ApplicationArguments;

@ExtendWith(MockitoExtension.class)
class AgendadorInicializacaoRunnerTest {

    @Mock
    private FonteDadoRepository fonteDadoRepository;

    @Mock
    private Scheduler scheduler;

    @Mock
    private ApplicationArguments args;

    @Test
    void deveCriarTriggerQuandoAindaNaoExiste() throws SchedulerException {
        AgendadorInicializacaoRunner runner = new AgendadorInicializacaoRunner(fonteDadoRepository,
                new AgendamentoFonteService(scheduler));
        FonteDado fonte = FonteDado.builder().id(1L).nome("CAR").cronExpressao("0 0 4 1 * ?").build();
        when(fonteDadoRepository.findByAtivoTrueAndCronExpressaoIsNotNull()).thenReturn(List.of(fonte));
        when(scheduler.checkExists(any(TriggerKey.class))).thenReturn(false);

        runner.run(args);

        ArgumentCaptor<JobDetail> jobCaptor = ArgumentCaptor.forClass(JobDetail.class);
        ArgumentCaptor<Trigger> triggerCaptor = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).scheduleJob(jobCaptor.capture(), triggerCaptor.capture());
        verify(scheduler, never()).rescheduleJob(any(TriggerKey.class), any(Trigger.class));

        assertThat(jobCaptor.getValue().getKey()).isEqualTo(JobKey.jobKey("ingestao-fonte-1", "ingestao"));
        assertThat(jobCaptor.getValue().getJobDataMap().getLong("fonteId")).isEqualTo(1L);
        assertThat(triggerCaptor.getValue().getKey()).isEqualTo(TriggerKey.triggerKey("trigger-fonte-1", "ingestao"));
    }

    @Test
    void deveAtualizarTriggerQuandoJaExiste() throws SchedulerException {
        AgendadorInicializacaoRunner runner = new AgendadorInicializacaoRunner(fonteDadoRepository,
                new AgendamentoFonteService(scheduler));
        FonteDado fonte = FonteDado.builder().id(2L).nome("IBAMA").cronExpressao("0 0 3 ? * MON").build();
        when(fonteDadoRepository.findByAtivoTrueAndCronExpressaoIsNotNull()).thenReturn(List.of(fonte));
        when(scheduler.checkExists(any(TriggerKey.class))).thenReturn(true);

        runner.run(args);

        verify(scheduler).rescheduleJob(eq(TriggerKey.triggerKey("trigger-fonte-2", "ingestao")), any(Trigger.class));
        verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    void naoDeveAgendarNadaQuandoNaoHaFontesAgendaveis() throws SchedulerException {
        AgendadorInicializacaoRunner runner = new AgendadorInicializacaoRunner(fonteDadoRepository,
                new AgendamentoFonteService(scheduler));
        when(fonteDadoRepository.findByAtivoTrueAndCronExpressaoIsNotNull()).thenReturn(List.of());

        runner.run(args);

        verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(Trigger.class));
        verify(scheduler, never()).rescheduleJob(any(TriggerKey.class), any(Trigger.class));
    }

    @Test
    void deveAgendarVariasFontesNaMesmaExecucao() throws SchedulerException {
        AgendadorInicializacaoRunner runner = new AgendadorInicializacaoRunner(fonteDadoRepository,
                new AgendamentoFonteService(scheduler));
        FonteDado fonteA = FonteDado.builder().id(1L).nome("CAR").cronExpressao("0 0 4 1 * ?").build();
        FonteDado fonteB = FonteDado.builder().id(2L).nome("IBAMA").cronExpressao("0 0 3 ? * MON").build();
        when(fonteDadoRepository.findByAtivoTrueAndCronExpressaoIsNotNull()).thenReturn(List.of(fonteA, fonteB));
        when(scheduler.checkExists(any(TriggerKey.class))).thenReturn(false);

        runner.run(args);

        verify(scheduler, times(2)).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    void devePropagarSchedulerExceptionAoFalharAgendamento() throws SchedulerException {
        AgendadorInicializacaoRunner runner = new AgendadorInicializacaoRunner(fonteDadoRepository,
                new AgendamentoFonteService(scheduler));
        FonteDado fonte = FonteDado.builder().id(3L).nome("INPE").cronExpressao("0 0 2 * * ?").build();
        when(fonteDadoRepository.findByAtivoTrueAndCronExpressaoIsNotNull()).thenReturn(List.of(fonte));
        when(scheduler.checkExists(any(TriggerKey.class))).thenThrow(new SchedulerException("falha simulada"));

        assertThatThrownBy(() -> runner.run(args)).isInstanceOf(SchedulerException.class);
    }
}