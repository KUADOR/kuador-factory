package ec.kuador.board.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Los listeners @ApplicationModuleListener corren en otro hilo, después del commit. */
@Configuration
@EnableAsync
class AsyncConfiguration {}
