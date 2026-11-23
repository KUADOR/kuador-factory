package ec.kuador.board.demo.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;

/**
 * Los datos del ERP de demostración: proveedores y pedidos de compra fijos, y las facturas que se registran (en memoria;
 * se pierden al reiniciar o con «reiniciar»). Las fechas se calculan desde hoy para que la demo siempre parezca reciente.
 */
@Service
public class DemoErp {

    public static final BigDecimal IVA = new BigDecimal("0.15");
    private static final int MAX_INVOICES = 500;

    public record Supplier(String ruc, String nombre, String correo) {}

    public record Item(String codigo, String descripcion, BigDecimal cantidad, String unidad, BigDecimal precioUnitario) {
        public BigDecimal subtotal() {
            return cantidad.multiply(precioUnitario).setScale(2, RoundingMode.HALF_UP);
        }
    }

    public record Order(String numero, String rucProveedor, String proveedor, LocalDate fecha, LocalDate entrega, String estado, List<Item> items) {
        public BigDecimal subtotal() {
            return items.stream().map(Item::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal iva() {
            return subtotal().multiply(IVA).setScale(2, RoundingMode.HALF_UP);
        }

        public BigDecimal total() {
            return subtotal().add(iva());
        }
    }

    public record Invoice(String registro, String numeroFactura, String rucProveedor, String numeroPedido, LocalDate fechaEmision, BigDecimal total,
            String observacion, java.time.Instant registradaEn) {}

    /** No se pudo registrar: el motivo, como lo diría el ERP. */
    public static class Rejected extends RuntimeException {
        public final int status;

        public Rejected(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final Clock clock;
    private final Map<String, Supplier> suppliers = new LinkedHashMap<>();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final List<Invoice> invoices = new CopyOnWriteArrayList<>();

    DemoErp(Clock clock) {
        this.clock = clock;
        reset();
    }

    /** Vuelve a los datos de ejemplo: todos los pedidos abiertos y ninguna factura registrada. */
    public synchronized void reset() {
        LocalDate today = LocalDate.now(clock);
        suppliers.clear();
        orders.clear();
        invoices.clear();
        add(new Supplier("1790012345001", "Cartones del Sur S.A.", "ventas@cartonesdelsur.example"));
        add(new Supplier("1791234567001", "Agroquímicos Andinos Cía. Ltda.", "facturacion@agroandinos.example"));
        add(new Supplier("0991122334001", "Transportes Frío Express S.A.", "cobros@frioexpress.example"));
        add(new Supplier("1792233445001", "Plásticos del Pacífico S.A.", "ventas@plasticospacifico.example"));
        order("OC-2026-0451", "1790012345001", today.minusDays(12), today.minusDays(2),
                item("CAJ-QB", "Caja de cartón quarter box para flor", "500", "u", "3.20"),
                item("SEP-50", "Separador de cartón 50 cm", "1000", "u", "0.18"));
        order("OC-2026-0452", "1791234567001", today.minusDays(10), today.minusDays(1),
                item("FUN-GR5", "Fungicida para rosas, envase 5 L", "40", "u", "48.50"),
                item("PRES-1L", "Preservante floral, envase 1 L", "120", "u", "6.75"));
        order("OC-2026-0453", "0991122334001", today.minusDays(8), today.minusDays(3),
                item("FLE-REF", "Flete refrigerado finca → aeropuerto UIO", "6", "viaje", "180.00"));
        order("OC-2026-0454", "1792233445001", today.minusDays(6), today.plusDays(2),
                item("CAP-60", "Capuchón plástico para ramo 60 cm", "8000", "u", "0.045"),
                item("LIG-TR", "Liga elástica para tallo", "20000", "u", "0.004"));
        order("OC-2026-0455", "1790012345001", today.minusDays(4), today.plusDays(5),
                item("CAJ-HB", "Caja de cartón half box para flor", "300", "u", "4.10"));
    }

    private void add(Supplier s) {
        suppliers.put(s.ruc(), s);
    }

    private void order(String numero, String ruc, LocalDate fecha, LocalDate entrega, Item... items) {
        orders.put(numero, new Order(numero, ruc, suppliers.get(ruc).nombre(), fecha, entrega, "RECIBIDO", List.of(items)));
    }

    private static Item item(String codigo, String descripcion, String cantidad, String unidad, String precio) {
        return new Item(codigo, descripcion, new BigDecimal(cantidad), unidad, new BigDecimal(precio));
    }

    public List<Supplier> suppliers(String texto) {
        String t = texto == null ? "" : texto.strip().toLowerCase(Locale.ROOT);
        return suppliers.values().stream().filter(s -> t.isEmpty() || s.nombre().toLowerCase(Locale.ROOT).contains(t) || s.ruc().contains(t)).toList();
    }

    public List<Order> orders(String numero, String proveedor, String estado) {
        String n = numero == null ? "" : numero.strip().toUpperCase(Locale.ROOT);
        String p = proveedor == null ? "" : proveedor.strip().toLowerCase(Locale.ROOT);
        String e = estado == null ? "" : estado.strip().toUpperCase(Locale.ROOT);
        return orders.values().stream()
                .filter(o -> n.isEmpty() || o.numero().contains(n))
                .filter(o -> p.isEmpty() || o.proveedor().toLowerCase(Locale.ROOT).contains(p) || o.rucProveedor().contains(p))
                .filter(o -> e.isEmpty() || o.estado().equals(e))
                .sorted(Comparator.comparing(Order::numero)).toList();
    }

    public Optional<Order> order(String numero) {
        return Optional.ofNullable(orders.get(numero == null ? "" : numero.strip().toUpperCase(Locale.ROOT)));
    }

    public List<Invoice> invoices() {
        return List.copyOf(invoices);
    }

    /**
     * Registra una factura de proveedor contra su pedido. Como un ERP real, rechaza lo que no puede ser (pedido que no
     * existe, proveedor distinto, pedido ya facturado, factura repetida); comparar precios es trabajo de quien registra.
     */
    public synchronized Invoice register(String numeroFactura, String ruc, String numeroPedido, LocalDate fecha, BigDecimal total, String observacion) {
        if (numeroFactura == null || numeroFactura.isBlank()) throw new Rejected(400, "Falta numero_factura.");
        if (total == null || total.signum() <= 0) throw new Rejected(400, "El total debe ser mayor que cero.");
        Order o = order(numeroPedido).orElseThrow(() -> new Rejected(404, "No existe el pedido de compra " + numeroPedido + "."));
        if (ruc == null || !ruc.strip().equals(o.rucProveedor())) {
            throw new Rejected(409, "El RUC " + ruc + " no es el del proveedor del pedido " + o.numero() + " (" + o.rucProveedor() + ", " + o.proveedor() + ").");
        }
        if (o.estado().equals("FACTURADO")) throw new Rejected(409, "El pedido " + o.numero() + " ya tiene una factura registrada.");
        if (invoices.stream().anyMatch(i -> i.numeroFactura().equals(numeroFactura.strip()) && i.rucProveedor().equals(o.rucProveedor()))) {
            throw new Rejected(409, "La factura " + numeroFactura + " de ese proveedor ya está registrada.");
        }
        if (invoices.size() >= MAX_INVOICES) throw new Rejected(429, "La demo tiene demasiadas facturas: reiníciala.");
        Invoice inv = new Invoice("REG-" + (1001 + invoices.size()), numeroFactura.strip(), o.rucProveedor(), o.numero(), fecha == null ? LocalDate.now(clock) : fecha,
                total.setScale(2, RoundingMode.HALF_UP), observacion == null ? null : observacion.strip(), clock.instant());
        invoices.add(inv);
        orders.put(o.numero(), new Order(o.numero(), o.rucProveedor(), o.proveedor(), o.fecha(), o.entrega(), "FACTURADO", o.items()));
        return inv;
    }

    /** Las facturas de ejemplo para enviar al buzón: una que coincide, una con un precio distinto y una sin pedido. */
    public List<SampleInvoice> samples() {
        LocalDate today = LocalDate.now(clock);
        Order a = orders.get("OC-2026-0451");
        Order b = orders.get("OC-2026-0452");
        Supplier c = suppliers.get("1790012345001");
        return List.of(
                new SampleInvoice("factura-coincide", "001-002-000004518", suppliers.get(a.rucProveedor()), a.numero(), today.minusDays(1), a.items()),
                new SampleInvoice("factura-precio-distinto", "002-001-000012093", suppliers.get(b.rucProveedor()), b.numero(), today.minusDays(1),
                        List.of(b.items().get(0), new Item("PRES-1L", "Preservante floral, envase 1 L", new BigDecimal("120"), "u", new BigDecimal("7.40")))),
                new SampleInvoice("factura-sin-pedido", "001-002-000004533", c, "OC-2026-0999", today,
                        List.of(new Item("CAJ-QB", "Caja de cartón quarter box para flor", new BigDecimal("200"), "u", new BigDecimal("3.20")))));
    }

    public record SampleInvoice(String file, String numero, Supplier proveedor, String pedido, LocalDate fecha, List<Item> items) {
        public BigDecimal subtotal() {
            return items.stream().map(Item::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal iva() {
            return subtotal().multiply(IVA).setScale(2, RoundingMode.HALF_UP);
        }

        public BigDecimal total() {
            return subtotal().add(iva());
        }
    }

    public List<String> orderNumbers() {
        return new ArrayList<>(orders.keySet());
    }
}
