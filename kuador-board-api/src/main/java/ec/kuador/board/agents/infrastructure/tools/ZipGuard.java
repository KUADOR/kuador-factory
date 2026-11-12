package ec.kuador.board.agents.infrastructure.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Antes de abrir un Office (xlsx, docx, que son zip): comprueba cuánto ocupa descomprimido. Un archivo de pocos KB
 * puede inflarse a gigas («bomba zip») y tumbar el servidor; se rechaza antes de leerlo. Cuenta los bytes reales al
 * descomprimir (no confía en los tamaños que declara el zip).
 */
final class ZipGuard {

    static final long MAX_UNZIPPED = 150L * 1024 * 1024;
    private static final int MAX_ENTRIES = 5_000;

    private ZipGuard() {}

    static void check(byte[] content) throws IOException {
        Path tmp = Files.createTempFile("adjunto-", ".zip");
        try {
            Files.write(tmp, content);
            try (ZipFile zip = new ZipFile(tmp.toFile())) {
                if (zip.size() > MAX_ENTRIES) throw new IOException("tiene demasiadas partes internas");
                long total = 0;
                byte[] buffer = new byte[64 * 1024];
                for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                    try (InputStream in = zip.getInputStream(e.nextElement())) {
                        for (int n; (n = in.read(buffer)) > 0; ) {
                            total += n;
                            if (total > MAX_UNZIPPED) throw new IOException("descomprimido supera " + MAX_UNZIPPED / (1024 * 1024) + " MB");
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
