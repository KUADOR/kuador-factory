package ec.kuador.board.github.domain;

import java.util.List;
import java.util.Optional;

/** Puerto de persistencia del módulo. La app es global; instalaciones y estados, del espacio actual. */
public interface GitHubStore {

    Optional<GitHubApp> app();

    void saveApp(GitHubApp app);

    List<Installation> installations();

    Optional<Installation> installation(long installationId);

    void saveInstallation(Installation installation);

    void deleteInstallation(long installationId);

    void saveState(FlowState state);

    /** Lo consume: un estado sirve una sola vez. */
    Optional<FlowState> takeState(String state);
}
