package ec.kuador.board.agents.domain;

/** El modelo no pudo producir una propuesta usable; el mensaje se muestra en el registro de ejecuciones. */
public class AgentModelException extends RuntimeException {

    public AgentModelException(String message) {
        super(message);
    }

    public AgentModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
