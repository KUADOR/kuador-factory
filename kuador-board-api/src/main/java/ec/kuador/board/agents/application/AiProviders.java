package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.AiProvider;
import ec.kuador.board.agents.domain.AiProviderStore;
import ec.kuador.board.agents.domain.BoardAgentModel;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los proveedores de IA que ofrece la plataforma (Claude, ChatGPT, DeepSeek…) y el adaptador que habla con cada uno.
 * Quien administra la plataforma edita su dirección, sus modelos y si están activos; nada de eso va fijo en el código.
 */
@Service
public class AiProviders {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,29}$");
    private static final Pattern MODEL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/@-]{0,79}$");

    private final AiProviderStore store;
    private final Map<AiProvider.Protocol, BoardAgentModel> adapters = new EnumMap<>(AiProvider.Protocol.class);
    private final Clock clock;

    AiProviders(AiProviderStore store, List<BoardAgentModel> adapters, Clock clock) {
        this.store = store;
        this.clock = clock;
        adapters.forEach(a -> this.adapters.put(a.protocol(), a));
    }

    @Transactional(readOnly = true)
    public List<AiProvider> all() {
        return store.all();
    }

    /** Los activos y con modelos: los que una empresa puede elegir. */
    @Transactional(readOnly = true)
    public List<AiProvider> offered() {
        return store.all().stream().filter(p -> p.active() && !p.models().isEmpty()).toList();
    }

    @Transactional(readOnly = true)
    public AiProvider require(String code) {
        return store.find(code).orElseThrow(() -> new NotFoundException("No existe el proveedor de IA " + code + "."));
    }

    /** Si algún proveedor activo ofrece ese modelo (para elegirlo en un agente). */
    @Transactional(readOnly = true)
    public boolean anyOffers(String model) {
        return offered().stream().anyMatch(p -> p.offers(model));
    }

    public BoardAgentModel adapter(AiProvider provider) {
        BoardAgentModel a = adapters.get(provider.protocol());
        if (a == null) throw new IllegalStateException("No hay adaptador para el protocolo " + provider.protocol());
        return a;
    }

    @Transactional
    public List<AiProvider> save(String code, String name, String protocol, String baseUrl, List<String> models, String keyHint, boolean active, int sortOrder) {
        String c = code == null ? "" : code.strip().toUpperCase();
        if (!CODE.matcher(c).matches()) throw new DomainException("El código va en mayúsculas, sin espacios (p. ej. OPENAI).");
        if (name == null || name.isBlank()) throw new DomainException("Ponle un nombre al proveedor.");
        AiProvider.Protocol p;
        try {
            p = AiProvider.Protocol.valueOf(protocol == null ? "" : protocol.strip());
        } catch (IllegalArgumentException e) {
            throw new DomainException("El protocolo es ANTHROPIC u OPENAI_COMPATIBLE.");
        }
        String url = baseUrl == null ? "" : baseUrl.strip();
        if (!validUrl(url)) throw new DomainException("La dirección de la API debe empezar con https:// (http:// solo para localhost).");
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        for (String m : models == null ? List.<String>of() : models) {
            String s = m == null ? "" : m.strip();
            if (s.isEmpty()) continue;
            if (!MODEL.matcher(s).matches()) throw new DomainException("El modelo «" + s + "» tiene caracteres no válidos.");
            clean.add(s);
        }
        String hint = keyHint == null || keyHint.isBlank() ? null : keyHint.strip();
        if (hint != null && hint.length() > 120) throw new DomainException("La ayuda de la clave tiene máximo 120 caracteres.");
        store.save(new AiProvider(c, name.strip(), p, url, new ArrayList<>(clean), hint, active, sortOrder), clock.instant());
        return store.all();
    }

    private static boolean validUrl(String url) {
        try {
            URI u = URI.create(url);
            if ("https".equals(u.getScheme()) && u.getHost() != null) return true;
            return "http".equals(u.getScheme()) && ("localhost".equals(u.getHost()) || "127.0.0.1".equals(u.getHost()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
