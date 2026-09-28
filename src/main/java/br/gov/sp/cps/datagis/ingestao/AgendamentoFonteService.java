package br.gov.sp.cps.datagis.ingestao;

import br.gov.sp.cps.datagis.controle.FonteDado;
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
import org.springframework.stereotype.Service;

/**
 * Mantem o Quartz em dia com o que esta gravado em controle.fonte_dado.
 *
 * Antes desta classe, o agendamento vivia dentro do
 * AgendadorInicializacaoRunner
 * e por isso so acontecia na subida do backend: mudar o cron pela tela nao
 * valia
 * nada ate reiniciar. Aqui a mesma logica fica disponivel para qualquer
 * momento,
 * e o runner e o FonteDadoService passam a usar exatamente o mesmo caminho.
 */
@Service
public class AgendamentoFonteService {

    private static final Logger log = LoggerFactory.getLogger(AgendamentoFonteService.class);
    private static final String GRUPO = "ingestao";

    private final Scheduler scheduler;

    public AgendamentoFonteService(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Cria, atualiza ou remove o agendamento da fonte conforme o estado dela.
     * Fonte ativa com cron passa a valer na hora; fonte desativada ou que ficou
     * sem cron perde o trigger, em vez de continuar disparando sozinha.
     */
    public void sincronizar(FonteDado fonte) throws SchedulerException {
        if (deveAgendar(fonte)) {
            agendar(fonte);
        } else {
            remover(fonte.getId());
        }
    }

    /**
     * Apaga o JobDetail da fonte - o Quartz remove os triggers ligados a ele junto.
     */
    public void remover(Long fonteId) throws SchedulerException {
        JobKey jobKey = JobKey.jobKey("ingestao-fonte-" + fonteId, GRUPO);

        if (scheduler.checkExists(jobKey)) {
            scheduler.deleteJob(jobKey);
            log.info("Agendamento removido para a fonte {}", fonteId);
        }
    }

    /** Ativo nulo cai no padrao da coluna no banco (DEFAULT 1), que e ativo. */
    private boolean deveAgendar(FonteDado fonte) {
        return !Boolean.FALSE.equals(fonte.getAtivo())
                && fonte.getCronExpressao() != null
                && !fonte.getCronExpressao().isBlank();
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
            log.info("Trigger atualizado para a fonte {} ({}), cron = {}", fonte.getId(), fonte.getNome(),
                    fonte.getCronExpressao());
        } else {
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("Trigger criado para a fonte {} ({}), cron = {}", fonte.getId(), fonte.getNome(),
                    fonte.getCronExpressao());
        }
    }
}