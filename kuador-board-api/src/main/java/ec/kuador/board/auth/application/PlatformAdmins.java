package ec.kuador.board.auth.application;

import ec.kuador.board.auth.domain.Credential;
import ec.kuador.board.auth.domain.CredentialRepository;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quienes administran la plataforma (planes y licencias de todos los espacios): los correos de
 * {@code board.platform.admins}, separados por coma. Vacío = nadie.
 */
@Service
public class PlatformAdmins {

    private final Set<String> emails;
    private final CredentialRepository credentials;

    PlatformAdmins(@Value("${board.platform.admins:}") String admins, CredentialRepository credentials) {
        this.emails = Arrays.stream(admins.split(",")).map(String::strip).filter(e -> !e.isEmpty()).map(Credential::normalizeEmail)
                .collect(Collectors.toUnmodifiableSet());
        this.credentials = credentials;
    }

    @Transactional(readOnly = true)
    public boolean isAdmin(UUID memberId) {
        return !emails.isEmpty() && credentials.findByMemberId(memberId).map(c -> emails.contains(c.email())).orElse(false);
    }
}
