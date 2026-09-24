package br.gov.sp.cps.datagis.ingestao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.controle.FonteDado;
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

/**
 * Prova o que o fix<LIZARDS-10> precisa garantir: salvar uma fonte passa a
 * criar, atualizar ou remover o agendamento na hora, sem depender de restart.
 */
@ExtendWith(MockitoExtension.class)
class AgendamentoFonteServiceTest {

    @Mock
    private Scheduler scheduler;

    @Test
    void deveCriarTriggerQuandoAFonteAtivaAindaNaoTemAgendamento() throws SchedulerException {
        AgendamentoFonteService service = new AgendamentoFonteService(scheduler);
        FonteDado fonte = FonteDado.builder()
                .id(1L).nome("CAR").ativo(true).cronExpressao("0 0 2 * * ?").build();
        when(scheduler.checkExists(any(TriggerKey.class))).thenReturn(false);

        service.sincronizar(fonte);

        ArgumentCaptor<JobDetail> jobCaptor = ArgumentCaptor.forClass(JobDetail.class);
        ArgumentCaptor<Trigger> triggerCaptor = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).scheduleJob(jobCaptor.capture(), triggerCaptor.capture());

        assertThat(jobCaptor.getValue().getKey()).isEqualTo(JobKey.jobKey("ingestao-fonte-1", "ingestao"));
        assertThat(jobCaptor.getValue().getJobDataMap().getLong("fonteId")).isEqualTo(1L);
        assertThat(triggerCaptor.getValue().getKey())
                .isEqualTo(TriggerKey.triggerKey("trigger-fonte-1", "ingestao"));
    }

    @Test
    void deveReagendarQuandoOCronMudaEmUmaFonteJaAgendada() throws SchedulerException {
        AgendamentoFonteService service = new AgendamentoFonteService(scheduler);
        FonteDado fonte = FonteDado.builder()
                .id(2L).nome("IBAMA").ativo(true).cronExpressao("0 30 8 ? * MON").build();
        when(scheduler.checkExists(any(TriggerKey.class))).thenReturn(true);

        service.sincronizar(fonte);

        verify(scheduler).rescheduleJob(eq(TriggerKey.triggerKey("trigger-fonte-2", "ingestao")), any(Trigger.class));
        verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    void deveRemoverOAgendamentoQuandoAFonteEDesativada() throws SchedulerException {
        AgendamentoFonteService service = new AgendamentoFonteService(scheduler);
        FonteDado fonte = FonteDado.builder()
                .id(3L).nome("INPE").ativo(false).cronExpressao("0 0 2 * * ?").build();
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(true);

        service.sincronizar(fonte);

        verify(scheduler).deleteJob(JobKey.jobKey("ingestao-fonte-3", "ingestao"));
        verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(Trigger.class));
        verify(scheduler, never()).rescheduleJob(any(TriggerKey.class), any(Trigger.class));
    }

    @Test
    void deveRemoverOAgendamentoQuandoOCronEApagado() throws SchedulerException {
        AgendamentoFonteService service = new AgendamentoFonteService(scheduler);
        FonteDado fonte = FonteDado.builder()
                .id(4L).nome("ICMBio").ativo(true).cronExpressao(null).build();
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(true);

        service.sincronizar(fonte);

        verify(scheduler).deleteJob(JobKey.jobKey("ingestao-fonte-4", "ingestao"));
        verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    void naoDeveTentarApagarUmAgendamentoQueNaoExiste() throws SchedulerException {
        AgendamentoFonteService service = new AgendamentoFonteService(scheduler);
        FonteDado fonte = FonteDado.builder()
                .id(5L).nome("FUNAI").ativo(false).cronExpressao(null).build();
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(false);

        service.sincronizar(fonte);

        verify(scheduler, never()).deleteJob(any(JobKey.class));
    }
}