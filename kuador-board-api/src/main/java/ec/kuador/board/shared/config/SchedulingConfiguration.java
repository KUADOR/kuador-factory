package ec.kuador.board.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Tareas periódicas (p. ej. el latido de las conexiones en tiempo real). */
@Configuration
@EnableScheduling
class SchedulingConfiguration {}
