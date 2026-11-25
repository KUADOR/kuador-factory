package ec.kuador.board.shared.config;

import java.util.concurrent.Executor;
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
        return pool("eventos-", 8);
    }

    /** Avisos a personas (correo, Telegram, push): dependen de servicios de afuera, que a veces tardan. */
    @Bean(AsyncExecutors.NOTICES)
    ThreadPoolTaskExecutor noticesExecutor() {
        return pool("avisos-", 4);
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

    /**
     * Hilos fijos y cola sin tope. Nunca «que lo corra quien lo pidió»: quien lo pide es la petición que acaba de
     * confirmar su transacción y todavía tiene su conexión a la base; si además corre el listener, que pide otra, con
     * unas cuantas peticiones así se agota el pool y la API se queda 30 s sin conexiones. Una cola larga solo atrasa
     * lo secundario (actividad, avisos, tablero en vivo), y los eventos pendientes quedan registrados en la base.
     */
    private static ThreadPoolTaskExecutor pool(String prefix, int threads) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}
