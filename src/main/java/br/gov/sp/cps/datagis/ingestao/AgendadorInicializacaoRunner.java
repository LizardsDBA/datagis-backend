package br.gov.sp.cps.datagis.ingestao;

import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.FonteDadoRepository;
import java.util.List;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Roda uma vez, logo apos o backend subir: le as fontes ativas com
 * cron_expressao preenchida e registra (ou atualiza, se ja existir de uma
 * subida anterior) o JobDetail/Trigger correspondente no Scheduler. E aqui
 * que o agendamento passa a existir de verdade - ate aqui, IngestaoAgendadaJob
 * so existia como classe, sem ninguem chamar.
 */
@Component
public class AgendadorInicializacaoRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgendadorInicializacaoRunner.class);
    private static final String GRUPO = "ingestao";

    private final FonteDadoRepository fonteDadoRepository;
    private final Scheduler scheduler;

    public AgendadorInicializacaoRunner(FonteDadoRepository fonteDadoRepository, Scheduler scheduler) {
        this.fonteDadoRepository = fonteDadoRepository;
        this.scheduler = scheduler;
    }

    @Override
    public void run(ApplicationArguments args) throws SchedulerException {
        List<FonteDado> fontesAgendaveis = fonteDadoRepository.findByAtivoTrueAndCronExpressaoIsNotNull();

        for (FonteDado fonte : fontesAgendaveis) {
            agendar(fonte);
        }

        log.info("Agendamento Quartz inicializado: {} fonte(s) agendada(s).", fontesAgendaveis.size());
    }

    private void agendar(FonteDado fonte) throws SchedulerException {
        JobKey jobKey = JobKey.jobKey("ingestao-fonte-" + fonte.getId(), GRUPO);
        TriggerKey triggerKey = TriggerKey.triggerKey("trigger-fonte-" + fonte.getId(), GRUPO);

        JobDataMap dados = new JobDataMap();
        dados.put("fonteId", fonte.getId());

        JobDetail jobDetail = JobBuilder.newJob(IngestaoAgendadaJob.class)
                .withIdentity(jobKey)
                .usingJobData(dados)
                .storeDurably()
                .build();

        CronTrigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey)
                .forJob(jobKey)
                .withSchedule(CronScheduleBuilder.cronSchedule(fonte.getCronExpressao()))
                .build();

        if (scheduler.checkExists(triggerKey)) {
            scheduler.rescheduleJob(triggerKey, trigger);
            log.info("Trigger atualizado para fonte {} ({})", fonte.getId(), fonte.getNome());
        } else {
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("Trigger criado para fonte {} ({}), cron = {}", fonte.getId(), fonte.getNome(),
                    fonte.getCronExpressao());
        }
    }
}