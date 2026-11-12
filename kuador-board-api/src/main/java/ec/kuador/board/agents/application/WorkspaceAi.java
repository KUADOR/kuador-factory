package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.AgentModelException;
import ec.kuador.board.agents.domain.AgentTeamStore;
import ec.kuador.board.agents.domain.BoardAgentModel;
import ec.kuador.board.shared.crypto.SecretBox;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La clave de IA de la empresa: con ella trabajan los agentes en el tablero (modo SERVER). Se comprueba con
 * Anthropic antes de guardarla, se guarda cifrada y nunca se vuelve a mostrar (solo sus últimos caracteres).
 */
@Service
public class WorkspaceAi {

    /** Modelos que se ofrecen; el primero es el que se usa si no se elige otro. */
    public static final List<String> MODELS = List.of("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5");
    private static final Pattern KEY = Pattern.compile("^sk-ant-[A-Za-z0-9_-]{20,}$");

    private final AgentTeamStore store;
    private final BoardAgentModel model;
    private final SecretBox secrets;
    private final Clock clock;

    WorkspaceAi(AgentTeamStore store, BoardAgentModel model, SecretBox secrets, Clock clock) {
        this.store = store;
        this.model = model;
        this.secrets = secrets;
        this.clock = clock;
    }

    /** Lo que ve quien configura: si hay clave, su pista y el modelo por defecto. */
    public record Status(boolean configured, String keyHint, String defaultModel, List<String> models) {}

    /** Clave lista para usar (descifrada) y el modelo por defecto del espacio. */
    public record Key(String apiKey, String defaultModel) {}

    @Transactional(readOnly = true)
    public Status status() {
        return store.aiKey(TenantContext.require()).map(k -> new Status(true, k.hint(), k.defaultModel(), MODELS))
                .orElse(new Status(false, null, MODELS.getFirst(), MODELS));
    }

    /** Guarda la clave (si viene) y el modelo por defecto. Sin clave nueva, solo cambia el modelo. */
    @Transactional
    public Status save(String rawKey, String defaultModel) {
        UUID ws = TenantContext.require();
        String chosen = defaultModel == null || defaultModel.isBlank() ? MODELS.getFirst() : defaultModel.strip();
        if (!MODELS.contains(chosen)) throw new DomainException("Elige uno de los modelos de la lista.");
        String key = rawKey == null ? "" : rawKey.strip();
        if (key.isEmpty()) {
            AgentTeamStore.AiKey current = store.aiKey(ws).orElseThrow(() -> new DomainException("Pega la clave de API de Anthropic."));
            store.saveAiKey(ws, new AgentTeamStore.AiKey(current.encryptedKey(), current.hint(), chosen), clock.instant());
            return status();
        }
        if (!KEY.matcher(key).matches()) throw new DomainException("Esa no parece una clave de Anthropic: empieza con «sk-ant-». Cópiala completa de la consola.");
        try {
            model.checkKey(key);
        } catch (AgentModelException e) {
            throw new DomainException(e.getMessage());
        }
        store.saveAiKey(ws, new AgentTeamStore.AiKey(secrets.encrypt(key), "…" + key.substring(key.length() - 4), chosen), clock.instant());
        return status();
    }

    @Transactional
    public Status remove() {
        store.deleteAiKey(TenantContext.require());
        return status();
    }

    /** La clave del espacio actual, si hay. */
    @Transactional(readOnly = true)
    public Optional<Key> key() {
        return store.aiKey(TenantContext.require()).map(k -> new Key(secrets.decrypt(k.encryptedKey()), k.defaultModel()));
    }
}
