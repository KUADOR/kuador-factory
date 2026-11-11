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
import ec.kuador.board.notify.domain.TelegramGateway;
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
    private final TelegramGateway bot;
    private final AuthApi auth;
    private final MemberApi members;
    private final WorkspaceApi workspace;
    private final Clock clock;
    private final TransactionTemplate tx;

    NotificationService(NotifyStore store, List<Channel> channels, TelegramGateway bot, AuthApi auth, MemberApi members, WorkspaceApi workspace, Clock clock,
            PlatformTransactionManager txManager) {
        this.store = store;
        this.channels = channels.stream().collect(Collectors.toMap(Channel::kind, Function.identity()));
        this.bot = bot;
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
        return new View(settings(who.memberId()), auth.emailOf(who.memberId()).orElse(null), channels.get(Channel.Kind.EMAIL).available(),
                bot.configured(), bot.configured() ? bot.username() : null);
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
        if (!bot.configured()) throw new DomainException("El tablero todavía no tiene un bot de Telegram configurado.");
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
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
        if (s.telegram() && s.telegramChatId() != null && bot.configured()) {
            store.enqueue(new Notice(UUID.randomUUID(), workspaceId, memberId, Channel.Kind.TELEGRAM, String.valueOf(s.telegramChatId()), subject, body + footer,
                    link, 0, now), now);
            used.add(Channel.Kind.TELEGRAM);
        }
        return used;
    }

    // ---- Telegram: mensajes que llegan al bot ----

    /** Un mensaje al bot. «/start CÓDIGO» vincula el chat con la persona que pidió el enlace. */
    public void onTelegramMessage(long chatId, String text) {
        String t = text == null ? "" : text.strip();
        if (t.startsWith("/start ")) {
            String code = t.substring("/start ".length()).strip();
            Optional<NotifyStore.LinkTarget> target = tx.execute(s -> store.consumeLinkCode(hash(code), clock.instant()));
            if (target.isPresent()) {
                String name = TenantContext.call(target.get().workspaceId(), () -> {
                    store.saveSettings(target.get().workspaceId(), settings(target.get().memberId()).withTelegramChat(chatId), clock.instant());
                    return members.find(target.get().memberId()).map(MemberView::name).orElse("") + " · tablero de " + workspace.identity().name();
                });
                reply(chatId, "Listo, " + name + ". Te avisaré aquí cuando algo te necesite: una pregunta de un agente, una aprobación o un reparto de tareas.");
                return;
            }
            reply(chatId, "Ese enlace ya se usó o venció. Pide uno nuevo en el tablero: Mi cuenta → Notificaciones → Conectar Telegram.");
            return;
        }
        reply(chatId, "Este bot envía los avisos del tablero. Conéctalo desde Mi cuenta → Notificaciones → Conectar Telegram.");
    }

    private void reply(long chatId, String text) {
        try {
            bot.sendMessage(chatId, text);
        } catch (RuntimeException e) {
            log.warn("No se pudo responder en Telegram: {}", e.getMessage());
        }
    }

    // ---- Despacho ----

    /** Envía lo que está listo. Si un envío falla, lo reintenta más tarde; tras varios intentos queda como fallido. */
    public int dispatch(int limit) {
        Integer sent = tx.execute(s -> {
            int ok = 0;
            Instant now = clock.instant();
            for (Notice n : store.lockDue(now, limit)) {
                try {
                    channels.get(n.channel()).send(n.address(), n.subject(), n.body(), n.link());
                    store.markSent(n.id(), clock.instant());
                    ok++;
                } catch (RuntimeException e) {
                    int attempts = n.attempts() + 1;
                    if (attempts >= MAX_ATTEMPTS) {
                        store.markFailed(n.id(), attempts, e.getMessage());
                        log.warn("Aviso {} por {} descartado tras {} intentos: {}", n.id(), n.channel(), attempts, e.getMessage());
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
