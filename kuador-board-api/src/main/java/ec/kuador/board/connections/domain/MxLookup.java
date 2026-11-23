package ec.kuador.board.connections.domain;

import java.util.List;

/** Puerto: a qué servidores llega el correo de un dominio (sus registros MX), del de más prioridad al de menos. */
public interface MxLookup {

    /** Vacío si el dominio no tiene registros MX o no se pudo consultar. */
    List<String> mx(String domain);
}
