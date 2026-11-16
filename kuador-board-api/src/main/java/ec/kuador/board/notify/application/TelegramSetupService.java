package ec.kuador.board.notify.application;

import ec.kuador.board.notify.domain.NotifyStore;
import ec.kuador.board.notify.domain.TelegramBotConfig;
import ec.kuador.board.notify.domain.TelegramGateway;
import ec.kuador.board.notify.domain.TelegramGroup;
import ec.kuador.board.shared.crypto.SecretBox;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.tenant.TenantContext;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Telegram del espacio actual: su propio bot (lo crea la empresa con @BotFather y pega aquí el token, que se
 * guarda cifrado) y los grupos que reciben avisos del equipo. Cambiar de bot desvincula los chats y grupos del
 * anterior, porque un chat solo recibe mensajes del bot con el que habló.
 */
@Service
public class TelegramSetupService {

    private static final Logger log = LoggerFactory.getLogger(TelegramSetupService.class);
    private static final Pattern TOKEN = Pattern.compile("^\\d{5,15}:[A-Za-z0-9_-]{30,64}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final NotifyStore store;
    private final TelegramBots bots;
    private final TelegramGateway telegram;
    private final SecretBox secrets;
    private final NotificationService notifications;
    private final Clock clock;
    private final String publicUrl;

    TelegramSetupService(NotifyStore store, TelegramBots bots, TelegramGateway telegram, SecretBox secrets, NotificationService notifications,
            Clock clock, @Value("${board.public-url:http://localhost:8091}") String publicUrl) {
        this.store = store;
        this.bots = bots;
        this.telegram = telegram;
        this.secrets = secrets;
        this.notifications = notifications;
        this.clock = clock;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    /** {@code ownBot}: el del espacio; {@code platformBot}: el de respaldo de la plataforma (si hay). */
    public record Setup(String ownBot, String platformBot, List<TelegramGroup> groups) {}

    /** {@code url}: abre Telegram para elegir un grupo, agrega el bot y le pasa el código (lo mismo que escribir {@code command}). */
    public record GroupCode(String command, String bot, String url) {}

    @Transactional(readOnly = true)
    public Setup setup() {
        UUID ws = TenantContext.require();
        return new Setup(store.bot(ws).map(NotifyStore.StoredBot::username).orElse(null), bots.platform().map(TelegramBotConfig::username).orElse(null),
                store.groups(ws));
    }

    /** Conecta el bot propio: valida el token con Telegram, lo guarda cifrado y, en producción, registra su webhook. */
    @Transactional
    public Setup connect(String rawToken) {
        UUID ws = TenantContext.require();
        String token = rawToken == null ? "" : rawToken.strip();
        if (!TOKEN.matcher(token).matches()) throw new DomainException("Ese no parece un token de bot. Cópialo completo del mensaje de @BotFather (números:letras).");
        String username;
        try {
            username = telegram.username(token);
        } catch (RuntimeException e) {
            throw new DomainException("Telegram no aceptó ese token: " + e.getMessage());
        }
        Optional<TelegramBotConfig> before = bots.forWorkspace(ws);
        String secret = HexFormat.of().formatHex(randomBytes());
        if (publicUrl.startsWith("https://")) {
            try {
                telegram.setWebhook(token, publicUrl + "/api/telegram/webhook/" + ws, secret);
            } catch (RuntimeException e) {
                throw new DomainException("Telegram no aceptó el webhook del bot: " + e.getMessage());
            }
        }
        store.saveBot(new NotifyStore.StoredBot(ws, secrets.encrypt(token), username, secret), clock.instant());
        if (before.isEmpty() || !before.get().username().equalsIgnoreCase(username)) store.clearTelegramLinks(ws);
        before.filter(b -> !b.platform() && !b.token().equals(token)).ifPresent(this::forgetWebhook);
        return setup();
    }

    /** Quita el bot propio: el espacio vuelve al de la plataforma (si hay) y los chats se vuelven a vincular. */
    @Transactional
    public Setup disconnect() {
        UUID ws = TenantContext.require();
        bots.own(ws).ifPresent(this::forgetWebhook);
        store.deleteBot(ws);
        store.clearTelegramLinks(ws);
        return setup();
    }

    /** Código de un solo uso (15 minutos) para escribir en el grupo donde se agregó el bot. */
    @Transactional
    public GroupCode groupCode() {
        UUID ws = TenantContext.require();
        TelegramBotConfig bot = bots.forWorkspace(ws).orElseThrow(() -> new DomainException("Primero conecta el bot de Telegram del espacio."));
        String code = notifications.newGroupCode(ws);
        return new GroupCode("/vincular " + code, bot.username(), "https://t.me/" + bot.username() + "?startgroup=" + code);
    }

    @Transactional
    public TelegramGroup updateGroup(UUID groupId, boolean onAssigned, boolean onGates, boolean onPlans) {
        UUID ws = TenantContext.require();
        TelegramGroup g = store.groups(ws).stream().filter(x -> x.id().equals(groupId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Ese grupo ya no está vinculado."));
        TelegramGroup updated = new TelegramGroup(g.id(), ws, g.chatId(), g.title(), onAssigned, onGates, onPlans);
        store.saveGroup(updated, clock.instant());
        return updated;
    }

    @Transactional
    public void removeGroup(UUID groupId) {
        store.deleteGroup(TenantContext.require(), groupId);
    }

    private void forgetWebhook(TelegramBotConfig bot) {
        try {
            telegram.deleteWebhook(bot.token());
        } catch (RuntimeException e) {
            log.debug("No se pudo quitar el webhook de @{}: {}", bot.username(), e.getMessage());
        }
    }

    private static byte[] randomBytes() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return b;
    }
}
