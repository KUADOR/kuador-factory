package ec.kuador.board.shared.net;

import ec.kuador.board.shared.error.DomainException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Direcciones a las que el servidor puede llamar por pedido de un usuario (una conexión, una suscripción de avisos).
 * Solo HTTPS y solo hacia Internet: una dirección que resuelve a la red interna, a la propia máquina o al servicio de
 * metadatos de la nube se rechaza, para que nadie use el servidor como puente hacia adentro.
 */
public final class SafeUrls {

    private SafeUrls() {}

    /** La URI lista para llamar, o {@link DomainException} con el motivo. Se comprueba al guardar y en cada llamada. */
    public static URI requirePublicHttps(String url, boolean allowPrivate) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.strip());
        } catch (IllegalArgumentException e) {
            throw new DomainException("La dirección no es válida.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank() || uri.getUserInfo() != null) throw new DomainException("La dirección no es válida.");
        if (allowPrivate) {
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) throw new DomainException("La dirección debe empezar con https://.");
            return uri;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new DomainException("La dirección debe empezar con https://.");
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new DomainException("No se encuentra el servidor " + host + ".");
        }
        for (InetAddress a : addresses) {
            if (internal(a)) throw new DomainException("La dirección apunta a una red interna: solo se permiten servidores públicos.");
        }
        return uri;
    }

    static boolean internal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            // 100.64.0.0/10 (CGNAT), 0.0.0.0/8 y 198.18.0.0/15 (pruebas): tampoco son Internet.
            return first == 0 || (first == 100 && second >= 64 && second <= 127) || (first == 198 && (second == 18 || second == 19));
        }
        // fc00::/7 (direcciones locales únicas de IPv6).
        return (b[0] & 0xfe) == 0xfc;
    }
}
