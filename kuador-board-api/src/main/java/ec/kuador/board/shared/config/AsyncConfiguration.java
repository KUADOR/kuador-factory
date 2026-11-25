package ec.kuador.board.shared.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Los listeners corren en otro hilo, después del commit. Cada clase de trabajo tiene sus hilos, para que lo lento no
 * frene a lo rápido: un agente trabaja minutos; el tablero en vivo y la actividad, milisegundos.
 */
@Configuration
@EnableAsync
class AsyncConfiguration implements AsyncConfigurer {

    /** Lo corto: tablero en vivo, actividad, encadenar la siguiente etapa. Es el de todo {@code @Async} sin nombre. */
    @Bean(name = {"applicationTaskExecutor", "taskExecutor"})
    @Primary
    ThreadPoolTaskExecutor applicationTaskExecutor() {
        return pool("eventos-", 8, 16, 500);
    }

    /** Avisos a personas (correo, Telegram, push): dependen de servicios de afuera, que a veces tardan. */
    @Bean(AsyncExecutors.NOTICES)
    ThreadPoolTaskExecutor noticesExecutor() {
        return pool("avisos-", 4, 8, 1000);
    }

    /**
     * Los agentes: un hilo virtual por ejecución (casi todo el tiempo esperan al modelo). Cuántos trabajan a la vez
     * lo decide quien los despierta, no este ejecutor: limitar aquí frenaría a quien publica el evento.
     */
    @Bean(AsyncExecutors.AGENTS)
    SimpleAsyncTaskExecutor agentsExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("agente-");
        executor.setVirtualThreads(true);
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return applicationTaskExecutor();
    }

    /** Con la cola llena, lo corre quien lo pidió: va más lento, pero no se pierde. */
    private static ThreadPoolTaskExecutor pool(String prefix, int core, int max, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}
