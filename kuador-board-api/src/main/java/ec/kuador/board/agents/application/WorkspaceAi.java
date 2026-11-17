package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.AgentModelException;
import ec.kuador.board.agents.domain.AgentTeamStore;
import ec.kuador.board.agents.domain.AiProvider;
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
 * La IA de la empresa: el proveedor que eligió (Claude, ChatGPT, DeepSeek…) y su clave, con la que trabajan los
 * agentes en el tablero (modo SERVER). La clave se comprueba con el proveedor antes de guardarla, se guarda cifrada y
 * nunca se vuelve a mostrar (solo sus últimos caracteres). Un proveedor a la vez: cambiarlo pide su clave.
 */
@Service
public class WorkspaceAi implements AiKeyProvider {

    private static final Pattern ANTHROPIC_KEY = Pattern.compile("^sk-ant-[A-Za-z0-9_-]{20,}$");
    private static final Pattern GENERIC_KEY = Pattern.compile("^\\S{20,300}$");

    private final AgentTeamStore store;
    private final AiProviders providers;
    private final SecretBox secrets;
    private final Clock clock;
    private final AiUsage usage;
    private final BoardTeam team;

    WorkspaceAi(AgentTeamStore store, AiProviders providers, SecretBox secrets, Clock clock, AiUsage usage, BoardTeam team) {
        this.team = team;
        this.store = store;
        this.providers = providers;
        this.secrets = secrets;
        this.clock = clock;
        this.usage = usage;
    }

    /** Un proveedor que se puede elegir, con sus modelos (el primero es el recomendado). */
    public record ProviderOption(String code, String name, String keyHint, List<String> models, boolean codeExecution) {}

    /** Lo que ve quien configura: proveedor y pista de la clave guardada, el modelo por defecto y las opciones. */
    public record Status(boolean configured, String provider, String keyHint, String defaultModel, List<String> models, List<ProviderOption> providers) {}

    /** Clave lista para usar (descifrada), su proveedor y el modelo por defecto del espacio. */
    public record Key(AiProvider provider, String apiKey, String defaultModel) {

        public BoardAgentModel.Endpoint endpoint() {
            return new BoardAgentModel.Endpoint(provider.baseUrl(), apiKey);
        }

        /** El modelo de un agente si este proveedor lo ofrece; si no, el del espacio. */
        public String modelFor(String agentModel) {
            return provider.offers(agentModel) ? agentModel : defaultModel;
        }
    }

    @Transactional(readOnly = true)
    public Status status() {
        List<AiProvider> offered = providers.offered();
        List<ProviderOption> options = offered.stream()
                .map(p -> new ProviderOption(p.code(), p.name(), p.keyHint(), p.models(), p.protocol() == AiProvider.Protocol.ANTHROPIC)).toList();
        Optional<AgentTeamStore.AiKey> saved = store.aiKey(TenantContext.require());
        if (saved.isPresent()) {
            AgentTeamStore.AiKey k = saved.get();
            List<String> models = providers.require(k.provider()).models();
            return new Status(true, k.provider(), k.hint(), k.defaultModel(), models, options);
        }
        AiProvider first = offered.isEmpty() ? null : offered.getFirst();
        return new Status(false, first == null ? null : first.code(), null, first == null ? null : first.models().getFirst(),
                first == null ? List.of() : first.models(), options);
    }

    /**
     * Guarda el proveedor, la clave (si viene) y el modelo por defecto. Sin clave nueva solo cambia el modelo, y solo si
     * el proveedor es el mismo de la clave guardada.
     */
    @Transactional
    public Status save(String providerCode, String rawKey, String defaultModel) {
        UUID ws = TenantContext.require();
        Optional<AgentTeamStore.AiKey> current = store.aiKey(ws);
        String code = providerCode != null && !providerCode.isBlank() ? providerCode.strip()
                : current.map(AgentTeamStore.AiKey::provider).orElse("ANTHROPIC");
        AiProvider provider = providers.offered().stream().filter(p -> p.code().equals(code)).findFirst()
                .orElseThrow(() -> new DomainException("Ese proveedor de IA no está disponible."));
        String chosen = defaultModel == null || defaultModel.isBlank() ? provider.models().getFirst() : defaultModel.strip();
        if (!provider.offers(chosen)) throw new DomainException("Elige uno de los modelos de " + provider.name() + ".");
        String key = rawKey == null ? "" : rawKey.strip();
        if (key.isEmpty()) {
            AgentTeamStore.AiKey saved = current.filter(k -> k.provider().equals(provider.code()))
                    .orElseThrow(() -> new DomainException("Pega la clave de API de " + provider.name() + "."));
            store.saveAiKey(ws, new AgentTeamStore.AiKey(saved.provider(), saved.encryptedKey(), saved.hint(), chosen), clock.instant());
            return status();
        }
        if (provider.protocol() == AiProvider.Protocol.ANTHROPIC && !ANTHROPIC_KEY.matcher(key).matches()) {
            throw new DomainException("Esa no parece una clave de Anthropic: empieza con «sk-ant-». Cópiala completa de la consola.");
        }
        if (!GENERIC_KEY.matcher(key).matches()) throw new DomainException("Esa no parece una clave de API: cópiala completa, sin espacios.");
        try {
            providers.adapter(provider).checkKey(new BoardAgentModel.Endpoint(provider.baseUrl(), key));
        } catch (AgentModelException e) {
            throw new DomainException(e.getMessage());
        }
        store.saveAiKey(ws, new AgentTeamStore.AiKey(provider.code(), secrets.encrypt(key), "…" + key.substring(key.length() - 4), chosen), clock.instant());
        return status();
    }

    @Transactional
    public Status remove() {
        store.deleteAiKey(TenantContext.require());
        return status();
    }

    @Override
    public boolean overBudget() {
        return usage.overBudget();
    }

    /** Para el Agente PO, que por ahora solo trabaja con Claude: la clave del espacio si es de Anthropic. */
    @Override
    @Transactional(readOnly = true)
    public Optional<String> currentKey() {
        return key().filter(k -> k.provider().protocol() == AiProvider.Protocol.ANTHROPIC).map(Key::apiKey);
    }

    /** La clave del espacio actual, si hay. */
    @Transactional(readOnly = true)
    public Optional<Key> key() {
        return store.aiKey(TenantContext.require())
                .map(k -> new Key(providers.require(k.provider()), secrets.decrypt(k.encryptedKey()), k.defaultModel()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> modelForAgent(java.util.UUID agentId) {
        String chosen = team.choice(agentId).model();
        return key().filter(k -> k.provider().protocol() == AiProvider.Protocol.ANTHROPIC).map(k -> k.modelFor(chosen));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> effortForAgent(java.util.UUID agentId) {
        return key().filter(k -> k.provider().protocol() == AiProvider.Protocol.ANTHROPIC).flatMap(k -> Optional.ofNullable(team.choice(agentId).effort()));
    }
}
