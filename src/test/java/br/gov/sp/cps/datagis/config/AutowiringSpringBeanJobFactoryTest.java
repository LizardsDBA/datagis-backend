package br.gov.sp.cps.datagis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.ingestao.IngestaoAgendadaJob;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.TriggerBuilder;
import org.quartz.spi.OperableTrigger;
import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;

@ExtendWith(MockitoExtension.class)
class AutowiringSpringBeanJobFactoryTest {

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private AutowireCapableBeanFactory beanFactory;

    @Test
    void deveAutowirarInstanciaDoJobCriadaPeloSuper() throws Exception {
        when(applicationContext.getAutowireCapableBeanFactory()).thenReturn(beanFactory);

        AutowiringSpringBeanJobFactory factory = new AutowiringSpringBeanJobFactory();
        factory.setApplicationContext(applicationContext);

        JobDetail jobDetail = JobBuilder.newJob(IngestaoAgendadaJob.class)
                .withIdentity("job-teste", "grupo")
                .storeDurably()
                .build();
        OperableTrigger trigger = (OperableTrigger) TriggerBuilder.newTrigger()
                .withIdentity("trigger-teste", "grupo")
                .forJob(jobDetail)
                .startNow()
                .build();
        TriggerFiredBundle bundle = new TriggerFiredBundle(
                jobDetail, trigger, null, false, new Date(), null, null, null);

        Object job = factory.createJobInstance(bundle);

        assertThat(job).isInstanceOf(IngestaoAgendadaJob.class);
        verify(beanFactory).autowireBean(job);
    }
}