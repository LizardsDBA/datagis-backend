package br.gov.sp.cps.datagis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

class AsyncConfigTest {

    @Test
    @DisplayName("Cria executor datagisTaskExecutor e propaga contexto de segurança")
    void executorPropagaContextoSeguranca() throws InterruptedException {
        AsyncConfig config = new AsyncConfig();
        Executor executor = config.datagisTaskExecutor();

        assertThat(executor).isNotNull();
        assertThat(config.getAsyncExecutor()).isNotNull();

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("operador-teste", "senha"));
        SecurityContextHolder.setContext(context);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean autenticadoNaThread = new AtomicBoolean(false);

        executor.execute(() -> {
            SecurityContext threadCtx = SecurityContextHolder.getContext();
            if (threadCtx != null && threadCtx.getAuthentication() != null
                    && "operador-teste".equals(threadCtx.getAuthentication().getName())) {
                autenticadoNaThread.set(true);
            }
            latch.countDown();
        });

        boolean executou = latch.await(5, TimeUnit.SECONDS);
        SecurityContextHolder.clearContext();

        assertThat(executou).isTrue();
        assertThat(autenticadoNaThread.get()).isTrue();
    }

    @Test
    @DisplayName("Handler de exceção assíncrona não lança erro")
    void uncaughtExceptionHandlerNaoFalha() throws NoSuchMethodException {
        AsyncConfig config = new AsyncConfig();
        AsyncUncaughtExceptionHandler handler = config.getAsyncUncaughtExceptionHandler();

        assertThat(handler).isNotNull();

        Method method = String.class.getMethod("toString");
        assertThatCode(() -> handler.handleUncaughtException(new RuntimeException("Erro simulado"), method, "arg1"))
                .doesNotThrowAnyException();
    }
}
