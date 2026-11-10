package ec.kuador.board.testing;

import ec.kuador.board.shared.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Fija un espacio de trabajo durante cada prueba unitaria (los servicios publican eventos con él). */
public class TestTenant implements BeforeEachCallback, AfterEachCallback {

    public static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Override
    public void beforeEach(ExtensionContext context) {
        TenantContext.set(WORKSPACE);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        TenantContext.clear();
    }
}
