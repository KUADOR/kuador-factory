package ec.kuador.board.agents.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Proveedores de IA de la plataforma. */
public interface AiProviderStore {

    List<AiProvider> all();

    Optional<AiProvider> find(String code);

    void save(AiProvider provider, Instant now);
}
