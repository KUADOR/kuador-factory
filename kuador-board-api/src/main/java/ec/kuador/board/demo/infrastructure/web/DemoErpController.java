package ec.kuador.board.demo.infrastructure.web;

import ec.kuador.board.demo.application.DemoErp;
import ec.kuador.board.demo.application.SampleInvoicePdf;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * La API del ERP de demostración, como la publicaría una empresa para su tienda en línea: JSON en snake_case y clave en
 * la cabecera X-API-Key. La documentación y las facturas de ejemplo son públicas; los datos piden la clave.
 */
@RestController
class DemoErpController {

    private final DemoErp erp;
    private final byte[] apiKey;
    private final String key;
    private final String spec;
    private final String publicUrl;

    DemoErpController(DemoErp erp, @Value("${board.demo-erp.key:demo-flores-2026}") String apiKey, @Value("${board.public-url}") String publicUrl) throws IOException {
        this.erp = erp;
        this.key = apiKey;
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        try (InputStream in = new ClassPathResource("demo-erp/openapi.json").getInputStream()) {
            this.spec = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Para conectarlo desde «Conexiones» con un clic: dónde está su documentación, su clave y las facturas de ejemplo. */
    @GetMapping("/api/connection-directory/demo-erp")
    Map<String, Object> howToConnect() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "ERP de demostración (flores)");
        m.put("specUrl", publicUrl + "/demo-erp/openapi.json");
        m.put("header", "X-API-Key");
        m.put("credential", key);
        m.put("operations", java.util.List.of("buscar_pedidos_compra", "obtener_pedido_compra", "listar_proveedores", "registrar_factura_proveedor",
                "listar_facturas_proveedor"));
        m.put("samples", erp.samples().stream().map(s -> Map.of("name", s.file() + ".pdf", "url", publicUrl + "/demo-erp/muestras/" + s.file() + ".pdf")).toList());
        return m;
    }

    @GetMapping(value = "/demo-erp/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    String openapi() {
        return spec;
    }

    @GetMapping(value = "/demo-erp/muestras/{file}.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> sample(@PathVariable String file) {
        return erp.samples().stream().filter(s -> s.file().equals(file)).findFirst()
                .map(s -> ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"" + s.file() + ".pdf\"").body(SampleInvoicePdf.render(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/demo-erp/proveedores")
    ResponseEntity<?> suppliers(@RequestHeader(value = "X-API-Key", required = false) String key, @RequestParam(required = false) String texto) {
        if (!authorized(key)) return unauthorized();
        return ResponseEntity.ok(erp.suppliers(texto).stream().map(s -> Map.of("ruc", s.ruc(), "nombre", s.nombre(), "correo", s.correo())).toList());
    }

    @GetMapping("/demo-erp/pedidos-compra")
    ResponseEntity<?> orders(@RequestHeader(value = "X-API-Key", required = false) String key, @RequestParam(required = false) String numero,
            @RequestParam(required = false) String proveedor, @RequestParam(required = false) String estado) {
        if (!authorized(key)) return unauthorized();
        return ResponseEntity.ok(erp.orders(numero, proveedor, estado).stream().map(DemoErpController::summary).toList());
    }

    @GetMapping("/demo-erp/pedidos-compra/{numero}")
    ResponseEntity<?> order(@RequestHeader(value = "X-API-Key", required = false) String key, @PathVariable String numero) {
        if (!authorized(key)) return unauthorized();
        return erp.order(numero).<ResponseEntity<?>>map(o -> ResponseEntity.ok(detail(o)))
                .orElseGet(() -> problem(HttpStatus.NOT_FOUND, "No existe el pedido de compra " + numero + "."));
    }

    record InvoiceRequest(String numero_factura, String ruc_proveedor, String numero_pedido, LocalDate fecha_emision, BigDecimal total, String observacion) {}

    @PostMapping("/demo-erp/facturas-proveedor")
    ResponseEntity<?> register(@RequestHeader(value = "X-API-Key", required = false) String key, @RequestBody InvoiceRequest r) {
        if (!authorized(key)) return unauthorized();
        try {
            DemoErp.Invoice i = erp.register(r.numero_factura(), r.ruc_proveedor(), r.numero_pedido(), r.fecha_emision(), r.total(), r.observacion());
            return ResponseEntity.status(HttpStatus.CREATED).body(invoice(i));
        } catch (DemoErp.Rejected e) {
            return problem(HttpStatus.valueOf(e.status), e.getMessage());
        }
    }

    @GetMapping("/demo-erp/facturas-proveedor")
    ResponseEntity<?> invoices(@RequestHeader(value = "X-API-Key", required = false) String key) {
        if (!authorized(key)) return unauthorized();
        return ResponseEntity.ok(erp.invoices().stream().map(DemoErpController::invoice).toList());
    }

    /** Vuelve a los datos de ejemplo (para repetir la demo). */
    @PostMapping("/demo-erp/reiniciar")
    ResponseEntity<?> reset(@RequestHeader(value = "X-API-Key", required = false) String key) {
        if (!authorized(key)) return unauthorized();
        erp.reset();
        return ResponseEntity.ok(Map.of("estado", "Datos de ejemplo restaurados."));
    }

    private boolean authorized(String key) {
        return key != null && MessageDigest.isEqual(key.strip().getBytes(StandardCharsets.UTF_8), apiKey);
    }

    private static ResponseEntity<?> unauthorized() {
        return problem(HttpStatus.UNAUTHORIZED, "Falta la cabecera X-API-Key o no es válida.");
    }

    private static ResponseEntity<?> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(Map.of("error", status.getReasonPhrase(), "detalle", detail));
    }

    private static Map<String, Object> summary(DemoErp.Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("numero", o.numero());
        m.put("ruc_proveedor", o.rucProveedor());
        m.put("proveedor", o.proveedor());
        m.put("fecha", o.fecha().toString());
        m.put("estado", o.estado());
        m.put("total", o.total());
        return m;
    }

    private static Map<String, Object> detail(DemoErp.Order o) {
        Map<String, Object> m = summary(o);
        m.put("fecha_entrega", o.entrega().toString());
        m.put("items", o.items().stream().map(i -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("codigo", i.codigo());
            x.put("descripcion", i.descripcion());
            x.put("cantidad", i.cantidad());
            x.put("unidad", i.unidad());
            x.put("precio_unitario", i.precioUnitario());
            x.put("subtotal", i.subtotal());
            return x;
        }).toList());
        m.put("subtotal", o.subtotal());
        m.put("iva", o.iva());
        return m;
    }

    private static Map<String, Object> invoice(DemoErp.Invoice i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("registro", i.registro());
        m.put("numero_factura", i.numeroFactura());
        m.put("ruc_proveedor", i.rucProveedor());
        m.put("numero_pedido", i.numeroPedido());
        m.put("fecha_emision", i.fechaEmision().toString());
        m.put("total", i.total());
        m.put("estado", "REGISTRADA");
        if (i.observacion() != null) m.put("observacion", i.observacion());
        m.put("registrada_en", i.registradaEn().toString());
        return m;
    }
}
