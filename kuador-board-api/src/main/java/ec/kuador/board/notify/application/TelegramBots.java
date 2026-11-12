package ec.kuador.board.notify.application;

import ec.kuador.board.notify.domain.NotifyStore;
import ec.kuador.board.notify.domain.TelegramBotConfig;
import ec.kuador.board.shared.crypto.SecretBox;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Qué bot avisa a cada espacio: el propio (configurado en la web, token cifrado) o, si no tiene, el de la
 * plataforma ({@code BOARD_TELEGRAM_BOT_TOKEN}/{@code _USERNAME}, opcional).
 */
@Component
public class TelegramBots {

    private final NotifyStore store;
    private final SecretBox secrets;
    private final Optional<TelegramBotConfig> platform;

    TelegramBots(NotifyStore store, SecretBox secrets, @Value("${board.telegram.bot-token:}") String token,
            @Value("${board.telegram.bot-username:}") String username) {
        this.store = store;
        this.secrets = secrets;
        String t = token == null ? "" : token.strip();
        String u = username == null ? "" : username.strip().replaceFirst("^@", "");
        this.platform = t.isEmpty() || u.isEmpty() ? Optional.empty() : Optional.of(new TelegramBotConfig(null, t, u, derivedSecret(t)));
    }

    public Optional<TelegramBotConfig> platform() {
        return platform;
    }

    /** El propio del espacio, si tiene. */
    public Optional<TelegramBotConfig> own(UUID workspaceId) {
        return store.bot(workspaceId).map(b -> new TelegramBotConfig(b.workspaceId(), secrets.decrypt(b.tokenEncrypted()), b.username(), b.webhookSecret()));
    }

    /** El que avisa a ese espacio. */
    public Optional<TelegramBotConfig> forWorkspace(UUID workspaceId) {
        return own(workspaceId).or(() -> platform);
    }

    /** Por su clave de webhook: {@code platform} o el id del espacio. */
    public Optional<TelegramBotConfig> byKey(String key) {
        if (TelegramBotConfig.PLATFORM.equals(key)) return platform;
        try {
            return own(UUID.fromString(key));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Todos los bots en uso (para escuchar sus mensajes). */
    public List<TelegramBotConfig> all() {
        List<TelegramBotConfig> all = new ArrayList<>();
        platform.ifPresent(all::add);
        for (NotifyStore.StoredBot b : store.bots()) {
            try {
                all.add(new TelegramBotConfig(b.workspaceId(), secrets.decrypt(b.tokenEncrypted()), b.username(), b.webhookSecret()));
            } catch (RuntimeException e) {
                // Un token que no se puede descifrar (cambió la clave) no debe frenar a los demás bots.
            }
        }
        return all;
    }

    /** Secreto del webhook del bot de la plataforma: derivado del token, para no tener otra variable que cuidar. */
    static String derivedSecret(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("webhook:" + token).getBytes(StandardCharsets.UTF_8))).substring(0, 48);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
