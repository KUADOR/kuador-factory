package ec.kuador.board.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Secreto compartido entre la web y el API ({@code BOARD_WEB_SECRET}, el mismo valor en los dos servicios). La web lo
 * manda en cada petición; con él el API sabe que la petición viene de la web: confía en la IP de la persona que la web
 * reenvía y, con el dominio público protegido, solo abre el resto de la API a la web.
 */
@Component
public class WebSecret {

    public static final String HEADER = "X-Kuador-Web-Secret";
    public static final String CLIENT_IP = "X-Kuador-Client-IP";
    public static final String CLIENT_AGENT = "X-Kuador-Client-Agent";

    private final byte[] secret;

    WebSecret(@Value("${board.web.secret:}") String secret) {
        this.secret = secret == null ? new byte[0] : secret.strip().getBytes(StandardCharsets.UTF_8);
    }

    public boolean configured() {
        return secret.length > 0;
    }

    /** Si la petición trae el secreto correcto (comparación en tiempo constante). */
    public boolean fromWeb(HttpServletRequest request) {
        String sent = request.getHeader(HEADER);
        return configured() && sent != null && MessageDigest.isEqual(secret, sent.strip().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * La IP de la persona: la que reenvía la web si la petición viene de la web; si no, la que vio nuestro proxy (el
     * último salto de X-Forwarded-For) o la conexión. Lo que diga un cliente cualquiera no cuenta.
     */
    public String clientIp(HttpServletRequest request) {
        String fromWeb = request.getHeader(CLIENT_IP);
        if (fromWeb != null && !fromWeb.isBlank() && fromWeb(request)) return fromWeb.strip();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            return hops[hops.length - 1].strip();
        }
        return request.getRemoteAddr();
    }

    public String clientAgent(HttpServletRequest request) {
        String fromWeb = request.getHeader(CLIENT_AGENT);
        return fromWeb != null && !fromWeb.isBlank() && fromWeb(request) ? fromWeb : request.getHeader("User-Agent");
    }
}
