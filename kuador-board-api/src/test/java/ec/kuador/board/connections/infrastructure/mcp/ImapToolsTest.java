package ec.kuador.board.connections.infrastructure.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.DummySSLSocketFactory;
import com.icegreen.greenmail.util.ServerSetupTest;
import ec.kuador.board.connections.ConnectionsApi;
import ec.kuador.board.connections.domain.Connection;
import ec.kuador.board.connections.domain.McpClient;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** El correo por IMAP y SMTP (Hostinger, cPanel…) contra un servidor de correo de prueba con SSL. */
class ImapToolsTest {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(ServerSetupTest.SMTPS_IMAPS);

    private GreenMailUser user;
    private HttpMcpClient client;
    private String url;
    private String secret;

    @BeforeEach
    void setUp() throws Exception {
        user = MAIL.setUser("facturas@flores.example", "facturas@flores.example", "clave-buzon");
        ec.kuador.board.connections.domain.MailAccount account = new ec.kuador.board.connections.domain.MailAccount("facturas@flores.example", "clave-buzon", "127.0.0.1", ServerSetupTest.IMAPS.getPort(), "127.0.0.1",
                ServerSetupTest.SMTPS.getPort(), true);
        url = account.url();
        secret = ImapTools.secret(account);
        client = new HttpMcpClient(true, List.of(new ImapTools(true, new DummySSLSocketFactory())));

        MimeMessage invoice = new MimeMessage(Session.getInstance(new Properties()));
        invoice.setFrom(new InternetAddress("ventas@cartones.example", "Cartones del Sur"));
        invoice.setRecipients(Message.RecipientType.TO, "facturas@flores.example");
        invoice.setSubject("Factura pedido 4471", "UTF-8");
        MimeBodyPart text = new MimeBodyPart();
        text.setText("Estimados, adjunto la factura del pedido 4471.", "UTF-8");
        MimeBodyPart pdf = new MimeBodyPart();
        pdf.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(ToolFilesTest.pdf("Factura 001-001-000012345", "Total: 1840.50 USD"), "application/pdf")));
        pdf.setFileName("factura.pdf");
        pdf.setDisposition(MimeBodyPart.ATTACHMENT);
        invoice.setContent(new MimeMultipart(text, pdf));
        invoice.setHeader("Message-ID", "<abc@cartones.example>");
        invoice.saveChanges();
        invoice.setHeader("Message-ID", "<abc@cartones.example>");
        user.deliver(invoice);

        MimeMessage hello = new MimeMessage(Session.getInstance(new Properties()));
        hello.setFrom(new InternetAddress("amigo@otro.example"));
        hello.setRecipients(Message.RecipientType.TO, "facturas@flores.example");
        hello.setSubject("Hola");
        hello.setText("Sin adjuntos.");
        user.deliver(hello);
    }

    @Test
    void connectsWithTheMailboxPasswordAndOffersTheSameToolsAsOutlookAndGmail() {
        assertThat(client.listTools(url, null, secret)).extracting(Connection.ToolDef::name)
                .containsExactlyElementsOf(NativeConnector.mailTools().stream().map(Connection.ToolDef::name).toList());
        String wrong = ImapTools.secret(new ec.kuador.board.connections.domain.MailAccount("facturas@flores.example", "otra", "127.0.0.1", ServerSetupTest.IMAPS.getPort(), "127.0.0.1",
                ServerSetupTest.SMTPS.getPort(), true));
        assertThatThrownBy(() -> client.listTools(url, null, wrong)).isInstanceOf(McpClient.UnauthorizedException.class).hasMessageContaining("contraseña");
        assertThatThrownBy(() -> new HttpMcpClient(false, List.of(new ImapTools(false, new DummySSLSocketFactory()))).listTools(url, null, secret))
                .as("nunca hacia la red interna").hasMessageContaining("red interna");
    }

    @Test
    void findsTheInvoiceReadsItsPdfAndMarksItRead() {
        String found = client.callTool(url, null, secret, "buscar_correos", Map.of("solo_no_leidos", true, "con_adjuntos", true));
        assertThat(found).contains("Factura pedido 4471", "Cartones del Sur <ventas@cartones.example>", "\"adjuntos\":true", "adjunto la factura")
                .doesNotContain("Hola");
        String id = found.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
        assertThat(id).endsWith(":INBOX");

        String mail = client.callTool(url, null, secret, "leer_correo", Map.of("id", id));
        assertThat(mail).contains("De: Cartones del Sur <ventas@cartones.example>", "Asunto: Factura pedido 4471", "- factura.pdf (application/pdf", "id: 2");

        assertThat(client.callTool(url, null, secret, "leer_adjunto", Map.of("correo", id, "adjunto", "2")))
                .startsWith("[Archivo «factura.pdf» (PDF, 1 página)]").contains("Total: 1840.50 USD");

        assertThat(client.callTool(url, null, secret, "marcar_leido", Map.of("id", id))).isEqualTo("Marcado como leído.");
        assertThat(client.callTool(url, null, secret, "buscar_correos", Map.of("solo_no_leidos", true, "con_adjuntos", true))).isEqualTo("No hay correos que cumplan eso.");
        assertThat(client.callTool(url, null, secret, "buscar_correos", Map.of("texto", "4471"))).contains("Factura pedido 4471").doesNotContain("Hola");
    }

    @Test
    void deliversNewMailWithItsFilesForTheBoardInbox() {
        var mail = client.recentMail(url, secret, null, java.time.Instant.now().minusSeconds(600), 10);
        assertThat(mail).extracting(ConnectionsApi.IncomingMail::subject).containsExactly("Factura pedido 4471", "Hola");
        assertThat(mail.getFirst().messageId()).isEqualTo("<abc@cartones.example>");
        assertThat(mail.getFirst().files()).singleElement().satisfies(f -> {
            assertThat(f.name()).isEqualTo("factura.pdf");
            assertThat(new String(f.content(), 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        });
        assertThat(client.recentMail(url, secret, null, java.time.Instant.now().plusSeconds(600), 10)).as("nada desde después").isEmpty();
        var again = client.recentMail(url, secret, null, java.time.Instant.now().minusSeconds(600), 10, java.util.Set.of("<abc@cartones.example>"));
        assertThat(again.getFirst().files()).as("lo ya procesado no se descarga otra vez").isEmpty();
        assertThat(again.getFirst().body()).isEmpty();
        assertThat(again.get(1).subject()).isEqualTo("Hola");
    }

    @Test
    void repliesAndSendsThroughSmtpAndKeepsACopyInSent() throws Exception {
        MAIL.getManagers().getImapHostManager().createMailbox(user, "Sent");

        String id = client.callTool(url, null, secret, "buscar_correos", Map.of("texto", "4471")).replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
        assertThat(client.callTool(url, null, secret, "responder_correo", Map.of("id", id, "cuerpo", "Recibida. El precio no cuadra con el pedido."))).isEqualTo("Respuesta enviada.");
        assertThat(client.callTool(url, null, secret, "enviar_correo", Map.of("para", List.of("compras@flores.example"), "asunto", "Revisar precio", "cuerpo", "Hola.")))
                .isEqualTo("Correo enviado a 1 destinatario.");

        MAIL.waitForIncomingEmail(5000, 2);
        MimeMessage reply = java.util.Arrays.stream(MAIL.getReceivedMessages()).filter(m -> {
            try {
                return m.getSubject().startsWith("Re:");
            } catch (Exception e) {
                return false;
            }
        }).findFirst().orElseThrow();
        assertThat(reply.getSubject()).isEqualTo("Re: Factura pedido 4471");
        assertThat(reply.getHeader("In-Reply-To")[0]).isEqualTo("<abc@cartones.example>");
        assertThat(reply.getAllRecipients()[0].toString()).contains("ventas@cartones.example");

        {
            Properties p = new Properties();
            p.put("mail.imaps.ssl.socketFactory", new DummySSLSocketFactory());
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            Store s = Session.getInstance(p).getStore("imaps");
            s.connect("127.0.0.1", ServerSetupTest.IMAPS.getPort(), "facturas@flores.example", "clave-buzon");
            Folder sent = s.getFolder("Sent");
            sent.open(Folder.READ_ONLY);
            assertThat(sent.getMessageCount()).as("SMTP no guarda la copia: la guarda el tablero").isEqualTo(2);
            sent.close(false);
            s.close();
        }
    }
}
