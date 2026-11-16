package ec.kuador.board.notify.application;

import ec.kuador.board.auth.AuthApi;
import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.notify.domain.Channel;
import ec.kuador.board.notify.domain.Notice;
import ec.kuador.board.notify.domain.NoticeKind;
import ec.kuador.board.notify.domain.NotificationSettings;
import ec.kuador.board.notify.domain.NotifyStore;
import ec.kuador.board.notify.domain.TelegramBotConfig;
import ec.kuador.board.notify.domain.TelegramGateway;
import ec.kuador.board.notify.domain.TelegramGroup;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.ForbiddenException;
import ec.kuador.board.shared.tenant.TenantContext;
import ec.kuador.board.workspace.WorkspaceApi;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Preferencias de avisos de cada persona, vínculo con Telegram y la cola de envíos. Encolar resuelve el destino
 * (correo o chat) en el momento; despachar envía con reintentos y espera creciente.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    static final int MAX_ATTEMPTS = 6;
    private static final Duration LINK_TTL = Duration.ofMinutes(15);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final NotifyStore store;
    private final Map<Channel.Kind, Channel> channels;
    private final TelegramGateway telegram;
    private final TelegramBots bots;
    private final AuthApi auth;
    private final MemberApi members;
    private final WorkspaceApi workspace;
    private final Clock clock;
    private final TransactionTemplate tx;

    NotificationService(NotifyStore store, List<Channel> channels, TelegramGateway telegram, TelegramBots bots, AuthApi auth, MemberApi members,
            WorkspaceApi workspace, Clock clock, PlatformTransactionManager txManager) {
        this.store = store;
        this.channels = channels.stream().collect(Collectors.toMap(Channel::kind, Function.identity()));
        this.telegram = telegram;
        this.bots = bots;
        this.auth = auth;
        this.members = members;
        this.workspace = workspace;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Lo que ve la persona en Mi cuenta. */
    public record View(NotificationSettings settings, String email, boolean emailAvailable, boolean telegramAvailable, String telegramBot) {}

    public View view(AuthenticatedMember who) {
        requirePerson(who);
        Optional<TelegramBotConfig> bot = bots.forWorkspace(who.workspaceId());
        return new View(settings(who.memberId()), auth.emailOf(who.memberId()).orElse(null), channels.get(Channel.Kind.EMAIL).available(),
                bot.isPresent(), bot.map(TelegramBotConfig::username).orElse(null));
    }

    /** Guarda canales y avisos; el chat de Telegram no se toca aquí (se vincula con el bot). */
    public View update(AuthenticatedMember who, boolean email, boolean telegram, boolean onAssigned, boolean onGates, boolean onPlans) {
        requirePerson(who);
        NotificationSettings current = settings(who.memberId());
        store.saveSettings(who.workspaceId(), new NotificationSettings(who.memberId(), email, telegram, current.telegramChatId(), onAssigned, onGates, onPlans),
                clock.instant());
        return view(who);
    }

    /** Enlace para vincular Telegram: abre el bot con un código de un solo uso. */
    public String telegramLink(AuthenticatedMember who) {
        requirePerson(who);
        TelegramBotConfig bot = bots.forWorkspace(who.workspaceId())
                .orElseThrow(() -> new DomainException("Tu espacio todavía no tiene un bot de Telegram. Configúralo en Conexiones → Telegram."));
        String code = newCode();
        store.saveLinkCode(hash(code), who.workspaceId(), who.memberId(), clock.instant().plus(LINK_TTL));
        return "https://t.me/" + bot.username() + "?start=" + code;
    }

    public View unlinkTelegram(AuthenticatedMember who) {
        requirePerson(who);
        store.saveSettings(who.workspaceId(), settings(who.memberId()).withTelegramChat(null), clock.instant());
        return view(who);
    }

    /** Un aviso de prueba por cada canal encendido; devuelve los canales usados. */
    public List<Channel.Kind> test(AuthenticatedMember who) {
        requirePerson(who);
        List<Channel.Kind> used = enqueue(who.workspaceId(), who.memberId(), NoticeKind.TEST, "Prueba de avisos del tablero",
                "Si lees esto, este canal funciona. Te avisaremos cuando algo te necesite.", null);
        if (used.isEmpty()) throw new DomainException("No tienes ningún canal encendido y disponible: activa el correo o vincula Telegram.");
        return used;
    }

    /**
     * Encola el aviso por cada canal que la persona tiene encendido y el servidor tiene configurado. Corre en el espacio
     * de la persona. Devuelve los canales usados.
     */
    public List<Channel.Kind> enqueue(UUID workspaceId, UUID memberId, NoticeKind kind, String subject, String body, String link) {
        NotificationSettings s = settings(memberId);
        if (!s.wants(kind)) return List.of();
        String footer = "\n\n— Tablero de " + workspace.identity().name();
        List<Channel.Kind> used = new ArrayList<>();
        Instant now = clock.instant();
        if (s.email() && channels.get(Channel.Kind.EMAIL).available()) {
            auth.emailOf(memberId).ifPresent(address -> {
                store.enqueue(new Notice(UUID.randomUUID(), workspaceId, memberId, Channel.Kind.EMAIL, address, subject, body + footer, link, 0, now), now);
                used.add(Channel.Kind.EMAIL);
            });
        }
        if (s.telegram() && s.telegramChatId() != null && bots.forWorkspace(workspaceId).isPresent()) {
            store.enqueue(new Notice(UUID.randomUUID(), workspaceId, memberId, Channel.Kind.TELEGRAM, String.valueOf(s.telegramChatId()), subject, body + footer,
                    link, 0, now), now);
            used.add(Channel.Kind.TELEGRAM);
        }
        return used;
    }

    /**
     * Un aviso para los grupos de Telegram del espacio que lo quieren (uno por evento, no uno por persona).
     * Corre en el espacio del aviso.
     */
    public void enqueueGroups(UUID workspaceId, NoticeKind kind, String subject, String body, String link) {
        if (bots.forWorkspace(workspaceId).isEmpty()) return;
        String footer = "\n\n— Tablero de " + workspace.identity().name();
        Instant now = clock.instant();
        for (TelegramGroup g : store.groups(workspaceId)) {
            if (!g.wants(kind)) continue;
            store.enqueue(new Notice(UUID.randomUUID(), workspaceId, null, Channel.Kind.TELEGRAM, String.valueOf(g.chatId()), subject, body + footer, link, 0, now), now);
        }
    }

    // ---- Telegram: mensajes que llegan a un bot ----

    /** Código para vincular un grupo: se escribe «/vincular CÓDIGO» en el grupo donde está el bot. */
    String newGroupCode(UUID workspaceId) {
        String code = newCode();
        store.saveLinkCode(hash(code), workspaceId, null, clock.instant().plus(LINK_TTL));
        return code;
    }

    /**
     * Un mensaje a un bot ({@code botKey}). En un chat directo, «/start CÓDIGO» vincula a la persona que pidió el
     * enlace; en un grupo, «/vincular CÓDIGO» vincula el grupo al espacio. El código debe ser de un espacio que avisa
     * con ese mismo bot.
     */
    public void onTelegramMessage(String botKey, long chatId, String chatType, String chatTitle, String text) {
        Optional<TelegramBotConfig> bot = bots.byKey(botKey);
        if (bot.isEmpty()) return;
        String t = text == null ? "" : text.strip();
        boolean group = "group".equals(chatType) || "supergroup".equals(chatType);
        if (group) {
            // En grupos el comando puede llegar como «/vincular@NombreDelBot CÓDIGO». Al agregar el bot con el enlace
            // «?startgroup=CÓDIGO» (el QR), Telegram envía en el grupo «/start@NombreDelBot CÓDIGO».
            if (!t.matches("(?s)^/(vincular|start)(@\\w+)?\\s+\\S+.*")) return;
            String code = t.replaceFirst("^/(vincular|start)(@\\w+)?\\s+", "").split("\\s+")[0];
            Optional<NotifyStore.LinkTarget> target = consume(code, bot.get()).filter(x -> x.memberId() == null);
            if (target.isEmpty()) {
                reply(bot.get(), chatId, "Ese código ya se usó o venció. Genera otro en el tablero: Conexiones → Telegram → Vincular grupo.");
                return;
            }
            String name = TenantContext.call(target.get().workspaceId(), () -> tx.execute(s -> {
                store.saveGroup(new TelegramGroup(UUID.randomUUID(), target.get().workspaceId(), chatId, chatTitle == null || chatTitle.isBlank() ? "Grupo" : chatTitle,
                        false, true, true), clock.instant());
                return workspace.identity().name();
            }));
            reply(bot.get(), chatId, "Listo: este grupo recibirá los avisos del tablero de " + name + " (aprobaciones y repartos de tareas). Ajústalo en Conexiones → Telegram.");
            return;
        }
        if (t.startsWith("/start ")) {
            String code = t.substring("/start ".length()).strip();
            Optional<NotifyStore.LinkTarget> target = consume(code, bot.get()).filter(x -> x.memberId() != null);
            if (target.isPresent()) {
                String name = TenantContext.call(target.get().workspaceId(), () -> tx.execute(s -> {
                    store.saveSettings(target.get().workspaceId(), settings(target.get().memberId()).withTelegramChat(chatId), clock.instant());
                    return members.find(target.get().memberId()).map(MemberView::name).orElse("") + " · tablero de " + workspace.identity().name();
                }));
                reply(bot.get(), chatId, "Listo, " + name + ". Te avisaré aquí cuando algo te necesite: una pregunta de un agente, una aprobación o un reparto de tareas.");
                return;
            }
            reply(bot.get(), chatId, "Ese enlace ya se usó o venció. Pide uno nuevo en el tablero: Mi cuenta → Notificaciones → Conectar Telegram.");
            return;
        }
        reply(bot.get(), chatId, "Este bot envía los avisos del tablero. Conéctalo desde Mi cuenta → Notificaciones → Conectar Telegram.");
    }

    /** Consume el código solo si su espacio avisa con el bot que recibió el mensaje (si no, el código sigue sirviendo). */
    private Optional<NotifyStore.LinkTarget> consume(String code, TelegramBotConfig receivedBy) {
        Optional<NotifyStore.LinkTarget> target = tx.execute(s -> store.findLinkCode(hash(code), clock.instant())
                .filter(x -> bots.forWorkspace(x.workspaceId()).map(TelegramBotConfig::key).filter(receivedBy.key()::equals).isPresent())
                .flatMap(x -> store.consumeLinkCode(hash(code), clock.instant())));
        return target == null ? Optional.empty() : target;
    }

    private void reply(TelegramBotConfig bot, long chatId, String text) {
        try {
            telegram.sendMessage(bot.token(), chatId, text);
        } catch (RuntimeException e) {
            log.warn("No se pudo responder en Telegram: {}", e.getMessage());
        }
    }

    private static String newCode() {
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // ---- Despacho ----

    /** Envía lo que está listo. Si un envío falla, lo reintenta más tarde; tras varios intentos queda como fallido. */
    public int dispatch(int limit) {
        Integer sent = tx.execute(s -> {
            int ok = 0;
            Instant now = clock.instant();
            for (Notice n : store.lockDue(now, limit)) {
                try {
                    channels.get(n.channel()).send(n.workspaceId(), n.address(), n.subject(), n.body(), n.link());
                    store.markSent(n.id(), clock.instant());
                    ok++;
                } catch (RuntimeException e) {
                    int attempts = n.attempts() + 1;
                    if (attempts >= MAX_ATTEMPTS) {
                        store.markFailed(n.id(), attempts, e.getMessage());
                        log.error("Aviso {} por {} descartado tras {} intentos: {}", n.id(), n.channel(), attempts, e.getMessage());
                    } else {
                        store.markRetry(n.id(), attempts, now.plus(Duration.ofSeconds(30L << Math.min(attempts, 7))), e.getMessage());
                    }
                }
            }
            return ok;
        });
        return sent == null ? 0 : sent;
    }

    // ---- Apoyo ----

    NotificationSettings settings(UUID memberId) {
        return store.settings(memberId).orElseGet(() -> NotificationSettings.defaults(memberId));
    }

    private static void requirePerson(AuthenticatedMember who) {
        if (who.agent() || who.runner()) throw new ForbiddenException("Los avisos son para personas.");
    }

    private static String hash(String code) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
