package ec.kuador.board.connections.infrastructure.dns;

import ec.kuador.board.connections.domain.MxLookup;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;
import org.springframework.stereotype.Component;

/** Los registros MX con el cliente DNS que trae Java, con tiempo de espera corto. */
@Component
class JndiMxLookup implements MxLookup {

    @Override
    public List<String> mx(String domain) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "2000");
        env.put("com.sun.jndi.dns.timeout.retries", "2");
        record Mx(int priority, String host) {}
        List<Mx> found = new ArrayList<>();
        try {
            InitialDirContext dns = new InitialDirContext(env);
            try {
                Attribute records = dns.getAttributes(domain, new String[] {"MX"}).get("MX");
                if (records == null) return List.of();
                NamingEnumeration<?> all = records.getAll();
                while (all.hasMore()) {
                    String[] parts = String.valueOf(all.next()).strip().split("\\s+");
                    if (parts.length < 2) continue;
                    int priority;
                    try {
                        priority = Integer.parseInt(parts[0]);
                    } catch (NumberFormatException e) {
                        priority = Integer.MAX_VALUE;
                    }
                    found.add(new Mx(priority, parts[1].replaceAll("\\.$", "").toLowerCase(Locale.ROOT)));
                }
            } finally {
                dns.close();
            }
        } catch (NamingException e) {
            return List.of();
        }
        return found.stream().sorted(Comparator.comparingInt(Mx::priority)).map(Mx::host).toList();
    }
}
