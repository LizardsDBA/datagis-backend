package br.gov.sp.cps.datagis.config;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

/**
 * Configuração de execução assíncrona do DataGIS para processamentos pesados
 * (ingestão de fontes, transição/revalidação Silver e migração Gold).
 *
 * <p>Utiliza um {@link ThreadPoolTaskExecutor} dedicado com prefixo de threads {@code datagis-async-},
 * envolvido por {@link DelegatingSecurityContextAsyncTaskExecutor} para propagar o contexto
 * de segurança e autenticação (Keycloak/RBAC) para as threads filhas.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean(name = "datagisTaskExecutor")
    public Executor datagisTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("datagis-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return new DelegatingSecurityContextAsyncTaskExecutor(executor);
    }

    @Override
    public Executor getAsyncExecutor() {
        return datagisTaskExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (Throwable ex, Method method, Object... params) ->
                log.error("Exceção não tratada em execução assíncrona no método {}: {}", method.getName(), ex.getMessage(), ex);
    }
}
