package br.gov.sp.cps.datagis.ingestao;

import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.FonteDadoRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.TimeZone;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final FonteDadoRepository fonteDadoRepository;

    /**
     * Fuso em que o horário escolhido na tela vale (LIZARDS-54). O container roda em UTC:
     * sem isso, "todo dia às 3:00" disparava às 3:00 UTC, meia-noite em Brasília.
     */
    private final ZoneId fuso;

    public AgendamentoFonteService(
            Scheduler scheduler,
            FonteDadoRepository fonteDadoRepository,
            @Value("${app.agendamento.fuso:America/Sao_Paulo}") String fuso) {
        this.scheduler = scheduler;
        this.fonteDadoRepository = fonteDadoRepository;
        this.fuso = ZoneId.of(fuso);
    }

    /**
     * Cria, atualiza ou remove o agendamento da fonte conforme o estado dela.
     * Fonte ativa com cron passa a valer na hora; fonte desativada ou que ficou
     * sem cron perde o trigger, em vez de continuar disparando sozinha.
     */
    public void sincronizar(FonteDado fonte) throws SchedulerException {
        if (deveAgendar(fonte)) {
            // Também na fonte em memória: quem salvou devolve ela na resposta da API
            fonte.setProximaExecucaoEm(agendar(fonte));
        } else {
            remover(fonte.getId());
            fonte.setProximaExecucaoEm(null);
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
        registrarProximaExecucao(fonteId, null);
    }

    /**
     * Grava na fonte quando o Quartz vai disparar de novo. Nulo = fonte sem agendamento.
     * No fuso do servidor, o mesmo de todas as outras datas gravadas (ultima_execucao_em,
     * data_add...), pra tela mostrar tudo no mesmo padrão. O horário em que a fonte roda
     * continua sendo o de {@link #fuso}.
     */
    public LocalDateTime registrarProximaExecucao(Long fonteId, Date proxima) {
        LocalDateTime proximaNoServidor = proxima == null
                ? null
                : LocalDateTime.ofInstant(proxima.toInstant(), ZoneId.systemDefault());
        fonteDadoRepository.atualizarProximaExecucao(fonteId, proximaNoServidor);
        return proximaNoServidor;
    }

    /**
     * Regrava a próxima execução lendo o trigger como ele está agora no Quartz. Usado pelo
     * job depois de cada disparo: se o agendamento foi desligado ou editado enquanto a
     * ingestão rodava, vale o que está no scheduler (nulo, se o trigger sumiu), e não o
     * horário que o job recebeu no começo da execução.
     */
    public void atualizarProximaExecucaoDoTrigger(Long fonteId) throws SchedulerException {
        Trigger trigger = scheduler.getTrigger(TriggerKey.triggerKey("trigger-fonte-" + fonteId, GRUPO));
        registrarProximaExecucao(fonteId, trigger != null ? trigger.getNextFireTime() : null);
    }

    /** Ativo nulo cai no padrao da coluna no banco (DEFAULT 1), que e ativo. */
    private boolean deveAgendar(FonteDado fonte) {
        return !Boolean.FALSE.equals(fonte.getAtivo())
                && fonte.getCronExpressao() != null
                && !fonte.getCronExpressao().isBlank();
    }

    private LocalDateTime agendar(FonteDado fonte) throws SchedulerException {
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
                .withSchedule(CronScheduleBuilder.cronSchedule(fonte.getCronExpressao())
                        .inTimeZone(TimeZone.getTimeZone(fuso)))
                .build();

        Date proxima;
        if (scheduler.checkExists(triggerKey)) {
            proxima = scheduler.rescheduleJob(triggerKey, trigger);
            log.info("Trigger atualizado para a fonte {} ({}), cron = {} ({}), proxima = {}", fonte.getId(),
                    fonte.getNome(), fonte.getCronExpressao(), fuso, proxima);
        } else {
            proxima = scheduler.scheduleJob(jobDetail, trigger);
            log.info("Trigger criado para a fonte {} ({}), cron = {} ({}), proxima = {}", fonte.getId(),
                    fonte.getNome(), fonte.getCronExpressao(), fuso, proxima);
        }
        return registrarProximaExecucao(fonte.getId(), proxima);
    }
}