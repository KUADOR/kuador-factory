package ec.kuador.board.github.infrastructure.persistence;

import ec.kuador.board.github.domain.FlowState;
import ec.kuador.board.github.domain.GitHubApp;
import ec.kuador.board.github.domain.GitHubStore;
import ec.kuador.board.github.domain.Installation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

interface SpringDataGitHubApps extends JpaRepository<GitHubAppEntity, Short> {}

interface SpringDataInstallations extends JpaRepository<InstallationEntity, UUID> {

    List<InstallationEntity> findAllByOrderByLinkedAtAsc();

    Optional<InstallationEntity> findByInstallationId(long installationId);

    @Modifying
    @Query("delete from InstallationEntity i where i.installationId = :installationId")
    void deleteByInstallationId(long installationId);
}

interface SpringDataFlowStates extends JpaRepository<FlowStateEntity, String> {}

@Repository
class JpaGitHubStore implements GitHubStore {

    private static final short APP = 1;

    private final SpringDataGitHubApps apps;
    private final SpringDataInstallations installations;
    private final SpringDataFlowStates states;

    JpaGitHubStore(SpringDataGitHubApps apps, SpringDataInstallations installations, SpringDataFlowStates states) {
        this.apps = apps;
        this.installations = installations;
        this.states = states;
    }

    @Override
    public Optional<GitHubApp> app() {
        return apps.findById(APP).map(GitHubAppEntity::toDomain);
    }

    @Override
    public void saveApp(GitHubApp app) {
        apps.save(GitHubAppEntity.from(app));
    }

    @Override
    public List<Installation> installations() {
        return installations.findAllByOrderByLinkedAtAsc().stream().map(InstallationEntity::toDomain).toList();
    }

    @Override
    public Optional<Installation> installation(long installationId) {
        return installations.findByInstallationId(installationId).map(InstallationEntity::toDomain);
    }

    @Override
    public void saveInstallation(Installation installation) {
        installations.save(InstallationEntity.from(installation));
    }

    @Override
    public void deleteInstallation(long installationId) {
        installations.deleteByInstallationId(installationId);
    }

    @Override
    public void saveState(FlowState state) {
        states.save(FlowStateEntity.from(state));
    }

    @Override
    public Optional<FlowState> takeState(String state) {
        Optional<FlowStateEntity> found = states.findById(state);
        found.ifPresent(states::delete);
        return found.map(FlowStateEntity::toDomain);
    }
}
