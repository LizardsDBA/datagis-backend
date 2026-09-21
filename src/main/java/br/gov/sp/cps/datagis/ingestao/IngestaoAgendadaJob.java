package br.gov.sp.cps.datagis.ingestao;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Job do Quartz que dispara a ingestão de uma fonte específica. O Quartz cria
 * uma
 * instância nova a cada disparo; o {@code fonteId} chega via
 * {@link org.quartz.JobDataMap}
 * (configurado no Trigger/JobDetail, criado num commit mais à frente). Daqui em
 * diante,
 * delega tudo pro {@link IngestaoExecucaoService}, que já sabe achar o executor
 * certo
 * pra essa fonte.
 */
public class IngestaoAgendadaJob implements Job {

    private static final String CHAVE_FONTE_ID = "fonteId";

    @Autowired
    private IngestaoExecucaoService ingestaoExecucaoService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Long fonteId = context.getMergedJobDataMap().getLong(CHAVE_FONTE_ID);
        try {
            ingestaoExecucaoService.executar(fonteId);
        } catch (Exception e) {
            throw new JobExecutionException(
                    "Falha ao executar ingestao agendada da fonte " + fonteId, e);
        }
    }
}