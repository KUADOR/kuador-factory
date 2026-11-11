package ec.kuador.board.github;

import java.util.List;

/** API pública: conexiones de GitHub del espacio actual y sus credenciales de corta duración. */
public interface GitHubApi {

    /** Una organización (o cuenta) de GitHub conectada al espacio con la app instalada. */
    record Connection(long installationId, String account, String accountType) {

        public boolean organization() {
            return "Organization".equals(accountType);
        }
    }

    List<Connection> connections();

    /** Conexión del espacio actual; DomainException si no existe. */
    Connection connection(long installationId);

    /** Token de instalación (dura una hora; se reutiliza mientras le quede margen). Solo para conexiones del espacio. */
    String installationToken(long installationId);
}
