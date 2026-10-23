package br.gov.sp.cps.datagis.ingestao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.FonteDadoRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.CronTrigger;
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

    @Mock
    private FonteDadoRepository fonteDadoRepository;

    @Test
    void deveCriarTriggerQuandoAFonteAtivaAindaNaoTemAgendamento() throws SchedulerException {
        AgendamentoFonteService service = new AgendamentoFonteService(scheduler, fonteDadoRepository, "America/Sao_Paulo");
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
        AgendamentoFonteService service = new AgendamentoFonteService(scheduler, fonteDadoRepository, "America/Sao_Paulo");
        FonteDado fonte = FonteDado.builder()
                .id(2L).nome("IBAMA").ativo(true).cronExpressao("0 30 8 ? * MON").build();
        when(scheduler.checkExists(any(TriggerKey.class))).thenReturn(true);

        service.sincronizar(fonte);

        verify(scheduler).rescheduleJob(eq(TriggerKey.triggerKey("trigger-fonte-2", "ingestao")), any(Trigger.class));
        verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    void deveRemoverOAgendamentoQuandoAFonteEDesativada() throws SchedulerException {
        AgendamentoFonteService service = new AgendamentoFonteService(scheduler, fonteDadoRepository, "America/Sao_Paulo");
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
        AgendamentoFonteService service = new AgendamentoFonteService(scheduler, fonteDadoRepository, "America/Sao_Paulo");
        FonteDado fonte = FonteDado.builder()
                .id(4L).nome("ICMBio").ativo(true).cronExpressao(null).build();
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(true);

        service.sincronizar(fonte);

        verify(scheduler).deleteJob(JobKey.jobKey("ingestao-fonte-4", "ingestao"));
        verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    void naoDeveTentarApagarUmAgendamentoQueNaoExiste() throws SchedulerException {
        AgendamentoFonteService service = new AgendamentoFonteService(scheduler, fonteDadoRepository, "America/Sao_Paulo");
        FonteDado fonte = FonteDado.builder()
                .id(5L).nome("FUNAI").ativo(false).cronExpressao(null).build();
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(false);

        service.sincronizar(fonte);

        verify(scheduler, never()).deleteJob(any(JobKey.class));
    }

    // LIZARDS-54: o container roda em UTC; o horário da tela tem que valer em Brasília
    @Test
    void deveAgendarNoFusoConfiguradoENaoNoFusoDoServidor() throws SchedulerException {
        AgendamentoFonteService service = new AgendamentoFonteService(scheduler, fonteDadoRepository, "America/Sao_Paulo");
        FonteDado fonte = FonteDado.builder()
                .id(6L).nome("Focos").ativo(true).cronExpressao("0 0 3 * * ?").build();
        when(scheduler.checkExists(any(TriggerKey.class))).thenReturn(false);

        service.sincronizar(fonte);

        ArgumentCaptor<Trigger> triggerCaptor = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).scheduleJob(any(JobDetail.class), triggerCaptor.capture());
        assertThat(((CronTrigger) triggerCaptor.getValue()).getTimeZone().getID()).isEqualTo("America/Sao_Paulo");
    }

    // A data gravada segue o fuso do servidor, como as outras datas da fonte
    @Test
    void deveGravarAProximaExecucaoNoFusoDoServidor() throws SchedulerException {
        AgendamentoFonteService service = new AgendamentoFonteService(scheduler, fonteDadoRepository, "America/Sao_Paulo");
        FonteDado fonte = FonteDado.builder()
                .id(7L).nome("CAR").ativo(true).cronExpressao("0 0 3 * * ?").build();
        Date proxima = Date.from(LocalDateTime.of(2026, 10, 24, 6, 0).atZone(ZoneId.of("UTC")).toInstant());
        LocalDateTime esperada = LocalDateTime.ofInstant(proxima.toInstant(), ZoneId.systemDefault());
        when(scheduler.checkExists(any(TriggerKey.class))).thenReturn(false);
        when(scheduler.scheduleJob(any(JobDetail.class), any(Trigger.class))).thenReturn(proxima);

        service.sincronizar(fonte);

        verify(fonteDadoRepository).atualizarProximaExecucao(7L, esperada);
        // e na fonte em memória, que é o que a API devolve logo depois de salvar
        assertThat(fonte.getProximaExecucaoEm()).isEqualTo(esperada);
    }

    @Test
    void deveLimparAProximaExecucaoQuandoOAgendamentoERemovido() throws SchedulerException {
        AgendamentoFonteService service = new AgendamentoFonteService(scheduler, fonteDadoRepository, "America/Sao_Paulo");
        FonteDado fonte = FonteDado.builder()
                .id(8L).nome("PRODES").ativo(true).cronExpressao(null)
                .proximaExecucaoEm(LocalDateTime.of(2026, 10, 24, 3, 0)).build();
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(true);

        service.sincronizar(fonte);

        verify(fonteDadoRepository).atualizarProximaExecucao(8L, null);
        assertThat(fonte.getProximaExecucaoEm()).isNull();
    }
}