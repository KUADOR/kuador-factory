package ec.kuador.board.connections.infrastructure.mcp;

import ec.kuador.board.connections.domain.Connection;
import ec.kuador.board.connections.domain.MailAccount;
import ec.kuador.board.connections.domain.McpClient.McpException;
import ec.kuador.board.connections.domain.McpClient.UnauthorizedException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.net.SafeUrls;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.BodyTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.OrTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import javax.net.ssl.SSLSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

/**
 * Correo de cualquier proveedor que hable IMAP y SMTP (Hostinger, cPanel, Zoho, GoDaddy…), con el correo y su
 * contraseña. Mismas herramientas que Outlook y Gmail. Solo conexiones cifradas (IMAP por SSL; SMTP por SSL o STARTTLS)
 * y solo servidores de Internet. El id de un correo es «uid:carpeta», y el de un adjunto, su parte («2», «1.3»).
 */
@Component
class ImapTools implements BuiltInConnector {

    private static final Logger log = LoggerFactory.getLogger(ImapTools.class);
    private static final String TIMEOUT = "20000";
    private static final int MAX_MAIL_TEXT = 30_000;
    private static final int MAX_FILE = 25 * 1024 * 1024;
    /** Correos que se revisan como mucho por búsqueda (los más recientes). */
    private static final int MAX_SCAN = 300;
    private static final Map<String, List<String>> SPECIAL = Map.of(
            "sentitems", List.of("\\Sent", "Sent", "INBOX.Sent", "Sent Items", "Sent Messages", "Enviados", "INBOX.Enviados", "Elementos enviados"),
            "drafts", List.of("\\Drafts", "Drafts", "INBOX.Drafts", "Borradores", "INBOX.Borradores"),
            "junkemail", List.of("\\Junk", "Junk", "Spam", "INBOX.Junk", "INBOX.spam", "Correo no deseado"),
            "archive", List.of("\\Archive", "Archive", "INBOX.Archive", "Archivo", "INBOX.Archivo"));

    private final boolean allowPrivate;
    /** Solo en pruebas: sockets que confían en el certificado del servidor de prueba. */
    private final SSLSocketFactory testSockets;

    @Autowired
    ImapTools(@Value("${board.connections.allow-private:false}") boolean allowPrivate) {
        this(allowPrivate, null);
    }

    ImapTools(boolean allowPrivate, SSLSocketFactory testSockets) {
        this.allowPrivate = allowPrivate;
        this.testSockets = testSockets;
    }

    static String secret(MailAccount a) {
        return NativeConnector.JSON.writeValueAsString(a);
    }

    static MailAccount account(String secret) {
        if (secret == null || secret.isBlank()) throw new UnauthorizedException("Falta la contraseña del correo.");
        try {
            return NativeConnector.JSON.readValue(secret, MailAccount.class);
        } catch (JacksonException | DomainException e) {
            throw new McpException("La credencial guardada no es de un correo IMAP: vuelve a conectar el correo.");
        }
    }

    @Override
    public boolean handles(String url) {
        return url != null && url.startsWith(MailAccount.SCHEME);
    }

    @Override
    public List<Connection.ToolDef> tools(String secret) {
        MailAccount a = account(secret);
        Store store = store(a);
        try {
            if (!store.getFolder("INBOX").exists()) throw new McpException("El correo no tiene bandeja de entrada.");
        } catch (MessagingException e) {
            throw new McpException("No se pudo leer el correo: " + e.getMessage());
        } finally {
            close(store);
        }
        smtpCheck(a);
        return NativeConnector.mailTools();
    }

    @Override
    public String call(String secret, String tool, Map<String, Object> a) {
        Map<String, Object> args = a == null ? Map.of() : a;
        MailAccount account = account(secret);
        Store store = store(account);
        try {
            return switch (tool) {
                case "buscar_correos" -> search(store, args);
                case "leer_correo" -> withMessage(store, NativeConnector.required(args, "id"), false, (f, m) -> read(m));
                case "leer_adjunto" -> withMessage(store, NativeConnector.required(args, "correo"), false,
                        (f, m) -> attachment(m, NativeConnector.required(args, "adjunto")));
                case "marcar_leido" -> withMessage(store, NativeConnector.required(args, "id"), true, (f, m) -> {
                    boolean read = !"false".equalsIgnoreCase(String.valueOf(args.get("leido")));
                    m.setFlag(Flags.Flag.SEEN, read);
                    return read ? "Marcado como leído." : "Marcado como no leído.";
                });
                case "responder_correo" -> withMessage(store, NativeConnector.required(args, "id"), true, (f, m) -> {
                    MimeMessage reply = (MimeMessage) m.reply(false);
                    reply.setFrom(new InternetAddress(account.email()));
                    reply.setText(NativeConnector.required(args, "cuerpo"), "UTF-8");
                    reply.setSentDate(new Date());
                    reply.saveChanges();
                    send(account, reply);
                    m.setFlag(Flags.Flag.ANSWERED, true);
                    keepSent(store, reply);
                    return "Respuesta enviada.";
                });
                case "enviar_correo" -> {
                    List<String> to = NativeConnector.recipients(args.get("para"));
                    MimeMessage mail = new MimeMessage(Session.getInstance(new Properties()));
                    mail.setFrom(new InternetAddress(account.email()));
                    mail.setRecipients(Message.RecipientType.TO, InternetAddress.parse(String.join(", ", to), true));
                    mail.setSubject(NativeConnector.required(args, "asunto").replaceAll("[\\r\\n]+", " "), "UTF-8");
                    mail.setText(NativeConnector.required(args, "cuerpo"), "UTF-8");
                    mail.setSentDate(new Date());
                    mail.saveChanges();
                    send(account, mail);
                    keepSent(store, mail);
                    yield "Correo enviado a " + to.size() + (to.size() == 1 ? " destinatario." : " destinatarios.");
                }
                default -> throw new McpException("El correo no tiene la herramienta «" + tool + "».");
            };
        } catch (MessagingException | IOException e) {
            throw new McpException("El servidor de correo respondió un error: " + e.getMessage());
        } finally {
            close(store);
        }
    }

    // ---- Herramientas ----

    private String search(Store store, Map<String, Object> args) throws MessagingException, IOException {
        Folder folder = folder(store, NativeConnector.text(args, "carpeta"));
        folder.open(Folder.READ_ONLY);
        try {
            List<SearchTerm> terms = new ArrayList<>();
            String since = NativeConnector.since(args);
            if (since != null) terms.add(new ReceivedDateTerm(ComparisonTerm.GE, Date.from(LocalDate.parse(since).atStartOfDay(ZoneOffset.UTC).toInstant())));
            if (NativeConnector.truthy(args.get("solo_no_leidos"))) terms.add(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            String words = NativeConnector.text(args, "texto");
            if (words != null) terms.add(new OrTerm(new SearchTerm[] {new SubjectTerm(words), new FromStringTerm(words), new BodyTerm(words)}));
            Message[] found;
            if (terms.isEmpty()) {
                int count = folder.getMessageCount();
                found = count == 0 ? new Message[0] : folder.getMessages(Math.max(1, count - MAX_SCAN + 1), count);
            } else {
                found = folder.search(terms.size() == 1 ? terms.getFirst() : new AndTerm(terms.toArray(SearchTerm[]::new)));
                if (found.length > MAX_SCAN) found = Arrays.copyOfRange(found, found.length - MAX_SCAN, found.length);
            }
            jakarta.mail.FetchProfile profile = new jakarta.mail.FetchProfile();
            profile.add(jakarta.mail.FetchProfile.Item.ENVELOPE);
            profile.add(jakarta.mail.FetchProfile.Item.FLAGS);
            profile.add(jakarta.mail.FetchProfile.Item.CONTENT_INFO);
            profile.add(UIDFolder.FetchProfileItem.UID);
            folder.fetch(found, profile);
            boolean withFiles = NativeConnector.truthy(args.get("con_adjuntos"));
            int max = NativeConnector.max(args);
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = found.length - 1; i >= 0 && out.size() < max; i--) {
                Message m = found[i];
                boolean hasFiles = !files(m, "", new ArrayList<>()).isEmpty();
                if (withFiles && !hasFiles) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", ((UIDFolder) folder).getUID(m) + ":" + folder.getFullName());
                row.put("de", addresses(m.getFrom()));
                row.put("asunto", m.getSubject() == null ? "" : m.getSubject());
                Date when = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
                row.put("fecha", when == null ? "" : when.toInstant().toString());
                row.put("leido", m.isSet(Flags.Flag.SEEN));
                row.put("adjuntos", hasFiles);
                String text = body(m);
                String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
                row.put("resumen", flat.length() > 200 ? flat.substring(0, 200) + "…" : flat);
                out.add(row);
            }
            return out.isEmpty() ? "No hay correos que cumplan eso." : NativeConnector.JSON.writeValueAsString(out);
        } finally {
            closeFolder(folder);
        }
    }

    private String read(Message m) throws MessagingException, IOException {
        StringBuilder out = new StringBuilder();
        out.append("De: ").append(addresses(m.getFrom())).append('\n');
        out.append("Para: ").append(addresses(m.getRecipients(Message.RecipientType.TO))).append('\n');
        String cc = addresses(m.getRecipients(Message.RecipientType.CC));
        if (!cc.isEmpty()) out.append("Copia: ").append(cc).append('\n');
        Date when = m.getSentDate() != null ? m.getSentDate() : m.getReceivedDate();
        out.append("Fecha: ").append(when == null ? "" : when.toInstant().toString()).append('\n');
        out.append("Asunto: ").append(m.getSubject() == null ? "" : m.getSubject()).append("\n\n");
        String body = body(m);
        body = body == null ? "" : body.strip();
        out.append(body.length() > MAX_MAIL_TEXT ? body.substring(0, MAX_MAIL_TEXT) + "\n… (correo recortado)" : body);
        List<Map.Entry<String, Part>> files = files(m, "", new ArrayList<>());
        if (!files.isEmpty()) {
            out.append("\n\nAdjuntos (léelos con leer_adjunto):\n");
            for (Map.Entry<String, Part> f : files) {
                Part p = f.getValue();
                out.append("- ").append(fileName(p)).append(" (").append(baseType(p)).append(", ").append(Math.max(1, p.getSize() / 1024)).append(" KB) id: ")
                        .append(f.getKey()).append('\n');
            }
        }
        return out.toString().strip();
    }

    private String attachment(Message m, String path) throws MessagingException, IOException {
        Part p = part(m, path);
        if (p == null) throw new McpException("Ese correo no tiene el adjunto " + NativeConnector.clip(path) + "; tómalo de leer_correo.");
        byte[] bytes;
        try (InputStream in = p.getInputStream()) {
            bytes = in.readNBytes(MAX_FILE + 1);
        }
        if (bytes.length > MAX_FILE) return "[Archivo «" + fileName(p) + "»: pesa demasiado para leerlo desde aquí.]";
        return ToolFiles.read(fileName(p), baseType(p), bytes);
    }

    // ---- Correo por dentro ----

    @FunctionalInterface
    private interface OnMessage {
        String apply(Folder folder, Message message) throws MessagingException, IOException;
    }

    private String withMessage(Store store, String id, boolean write, OnMessage work) throws MessagingException, IOException {
        int colon = id.indexOf(':');
        long uid;
        try {
            uid = Long.parseLong(colon < 0 ? "x" : id.substring(0, colon));
        } catch (NumberFormatException e) {
            throw new McpException("El id del correo no es válido; tómalo de buscar_correos.");
        }
        Folder folder = store.getFolder(id.substring(colon + 1));
        if (!folder.exists()) throw new McpException("No existe la carpeta de ese correo.");
        folder.open(write ? Folder.READ_WRITE : Folder.READ_ONLY);
        try {
            Message m = ((UIDFolder) folder).getMessageByUID(uid);
            if (m == null) throw new McpException("El correo ya no está en esa carpeta (¿se movió o se borró?).");
            return work.apply(folder, m);
        } finally {
            closeFolder(folder);
        }
    }

    private Folder folder(Store store, String name) throws MessagingException {
        if (name == null || name.equalsIgnoreCase("inbox")) return store.getFolder("INBOX");
        List<String> special = SPECIAL.get(name.toLowerCase(Locale.ROOT));
        if (special != null) {
            Folder found = special(store, special);
            if (found == null) throw new McpException("El correo no tiene la carpeta «" + name + "».");
            return found;
        }
        if (!name.matches("[\\p{L}\\p{N} ._/\\-]{1,100}")) throw new McpException("Carpeta no válida: usa inbox, sentitems, drafts, archive, junkemail o su nombre.");
        Folder f = store.getFolder(name);
        if (!f.exists()) throw new McpException("El correo no tiene la carpeta «" + name + "».");
        return f;
    }

    /** La carpeta marcada para ese uso (\Sent, \Drafts…) o, si el servidor no las marca, la de un nombre conocido. */
    private static Folder special(Store store, List<String> wanted) throws MessagingException {
        for (Folder f : store.getDefaultFolder().list("*")) {
            if (attributes(f).contains(wanted.getFirst())) return f;
        }
        for (String name : wanted.subList(1, wanted.size())) {
            Folder f = store.getFolder(name);
            if (f.exists()) return f;
        }
        return null;
    }

    /** Las marcas de uso de una carpeta (\Sent, \Drafts…). La implementación IMAP las da; la API estándar no. */
    private static List<String> attributes(Folder f) {
        try {
            return f.getClass().getMethod("getAttributes").invoke(f) instanceof String[] names ? Arrays.asList(names) : List.of();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return List.of();
        }
    }

    /** Guarda una copia de lo enviado en «Enviados» (SMTP no lo hace). Si no se puede, el correo igual salió. */
    private static void keepSent(Store store, MimeMessage sent) {
        try {
            Folder f = special(store, SPECIAL.get("sentitems"));
            if (f == null) return;
            sent.setFlag(Flags.Flag.SEEN, true);
            f.appendMessages(new Message[] {sent});
        } catch (MessagingException e) {
            log.info("No se guardó la copia en Enviados: {}", e.getMessage());
        }
    }

    /** Los adjuntos con su ruta de partes, sin las imágenes metidas en el cuerpo (firmas, logos). */
    private static List<Map.Entry<String, Part>> files(Part p, String path, List<Map.Entry<String, Part>> out) throws MessagingException, IOException {
        if (p.isMimeType("multipart/*") && p.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) files(mp.getBodyPart(i), path.isEmpty() ? String.valueOf(i + 1) : path + "." + (i + 1), out);
        } else if (isFile(p)) {
            out.add(Map.entry(path.isEmpty() ? "1" : path, p));
        }
        return out;
    }

    private static boolean isFile(Part p) throws MessagingException {
        String disposition = p.getDisposition();
        if (Part.ATTACHMENT.equalsIgnoreCase(disposition)) return true;
        if (p.getFileName() == null) return false;
        return !(Part.INLINE.equalsIgnoreCase(disposition) && p instanceof jakarta.mail.internet.MimePart mp && mp.getContentID() != null);
    }

    private static Part part(Message m, String path) throws MessagingException, IOException {
        if (!path.matches("\\d+(\\.\\d+)*")) return null;
        Part current = m;
        for (String step : path.split("\\.")) {
            int i = Integer.parseInt(step);
            if (current.isMimeType("multipart/*") && current.getContent() instanceof Multipart mp) {
                if (i < 1 || i > mp.getCount()) return null;
                current = mp.getBodyPart(i - 1);
            } else if (i == 1 && current == m) {
                return m;
            } else {
                return null;
            }
        }
        return current;
    }

    /** El texto del correo: la parte de texto plano o, si no hay, el HTML sin etiquetas. */
    private static String body(Part m) throws MessagingException, IOException {
        String text = text(m, "text/plain");
        if (text != null) return text;
        String html = text(m, "text/html");
        return html == null ? null : NativeConnector.plain(html);
    }

    private static String text(Part p, String type) throws MessagingException, IOException {
        if (p.isMimeType(type) && !isFile(p)) return p.getContent() instanceof String s ? s : null;
        if (p.isMimeType("multipart/*") && p.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                String found = text(mp.getBodyPart(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String fileName(Part p) throws MessagingException {
        String name = p.getFileName();
        if (name == null) return "adjunto";
        try {
            return MimeUtility.decodeText(name);
        } catch (java.io.UnsupportedEncodingException e) {
            return name;
        }
    }

    private static String baseType(Part p) throws MessagingException {
        try {
            return new ContentType(p.getContentType()).getBaseType().toLowerCase(Locale.ROOT);
        } catch (jakarta.mail.internet.ParseException e) {
            return "application/octet-stream";
        }
    }

    private static String addresses(jakarta.mail.Address[] list) {
        if (list == null) return "";
        List<String> out = new ArrayList<>();
        for (jakarta.mail.Address a : list) out.add(a instanceof InternetAddress ia ? ia.toUnicodeString() : a.toString());
        return String.join(", ", out);
    }

    // ---- Conexión con los servidores ----

    private Store store(MailAccount a) {
        requirePublic(a.imapHost());
        Properties p = new Properties();
        p.put("mail.imaps.connectiontimeout", TIMEOUT);
        p.put("mail.imaps.timeout", TIMEOUT);
        p.put("mail.imaps.writetimeout", TIMEOUT);
        secure(p, "imaps");
        try {
            Store store = Session.getInstance(p).getStore("imaps");
            store.connect(a.imapHost(), a.imapPort(), a.email(), a.password());
            return store;
        } catch (AuthenticationFailedException e) {
            throw new UnauthorizedException("El servidor de correo rechazó el correo o la contraseña.");
        } catch (MessagingException e) {
            throw new McpException("No se pudo conectar con " + a.imapHost() + ":" + a.imapPort() + " (IMAP por SSL): " + e.getMessage());
        }
    }

    /** Comprueba al conectar que también se puede enviar, para no enterarse en la primera respuesta. */
    private void smtpCheck(MailAccount a) {
        Transport t = transport(a);
        try {
            t.close();
        } catch (MessagingException e) {
            // Ya se comprobó al entrar.
        }
    }

    private void send(MailAccount a, MimeMessage mail) throws MessagingException {
        Transport t = transport(a);
        try {
            t.sendMessage(mail, mail.getAllRecipients());
        } finally {
            t.close();
        }
    }

    private Transport transport(MailAccount a) {
        requirePublic(a.smtpHost());
        // SSL desde el principio (465) o STARTTLS obligatorio (587): nunca en claro.
        String protocol = Boolean.TRUE.equals(a.smtpSsl()) ? "smtps" : "smtp";
        Properties p = new Properties();
        p.put("mail." + protocol + ".auth", "true");
        p.put("mail." + protocol + ".connectiontimeout", TIMEOUT);
        p.put("mail." + protocol + ".timeout", TIMEOUT);
        p.put("mail." + protocol + ".writetimeout", TIMEOUT);
        if (protocol.equals("smtp")) {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");
        }
        secure(p, protocol);
        try {
            Transport t = Session.getInstance(p).getTransport(protocol);
            t.connect(a.smtpHost(), a.smtpPort(), a.email(), a.password());
            return t;
        } catch (AuthenticationFailedException e) {
            throw new UnauthorizedException("El servidor de envío (SMTP) rechazó el correo o la contraseña.");
        } catch (MessagingException e) {
            throw new McpException("No se pudo conectar con el servidor de envío " + a.smtpHost() + ":" + a.smtpPort() + ": " + e.getMessage());
        }
    }

    private void secure(Properties p, String protocol) {
        if (testSockets != null) {
            p.put("mail." + protocol + ".ssl.socketFactory", testSockets);
            p.put("mail." + protocol + ".ssl.checkserveridentity", "false");
        } else {
            p.put("mail." + protocol + ".ssl.checkserveridentity", "true");
        }
    }

    private void requirePublic(String host) {
        try {
            SafeUrls.requirePublicHost(host, allowPrivate);
        } catch (DomainException e) {
            throw new McpException(e.getMessage());
        }
    }

    private static void closeFolder(Folder f) {
        try {
            if (f.isOpen()) f.close(false);
        } catch (MessagingException e) {
            // Nada que hacer: la conexión se cierra igual.
        }
    }

    private static void close(Store store) {
        try {
            store.close();
        } catch (MessagingException e) {
            // Idem.
        }
    }
}
