package ec.kuador.board.sync.infrastructure.files;

import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.sync.domain.RepositoryFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Archivos de una carpeta local. Defensas: solo rutas relativas .md sin "..", el destino debe quedar
 * dentro de la raíz real (resolviendo enlaces), no se siguen enlaces simbólicos y se escribe de forma
 * atómica (archivo temporal + rename).
 */
final class LocalFolderFiles implements RepositoryFiles {

    static final long MAX_BYTES = 1_000_000;
    private static final Pattern SAFE_FILE = Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*\\.md$");
    private static final Pattern SAFE_DIR = Pattern.compile("^$|^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$");

    private final Path root;

    /** {@code root} ya validada y real (sin enlaces). */
    LocalFolderFiles(Path root) {
        this.root = root;
    }

    @Override
    public Optional<String> read(String path) {
        Path file = resolve(path, true);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        rejectLink(file, path);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            insideRoot(file.toRealPath(), path);
            if (Files.size(file) > MAX_BYTES) {
                throw new DomainException(path + " pesa más de 1 MB; no se sincroniza.");
            }
            return Optional.of(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString());
        } catch (CharacterCodingException e) {
            throw new DomainException(path + " no está en UTF-8.");
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + path, e);
        }
    }

    /** Carpeta local: escribe directo, no hay PR. */
    @Override
    public Published publish(List<FileWrite> writes, String commitMessage, String title, String description, String existingBranch) {
        writes.forEach(w -> write(w.path(), w.content()));
        return new Published(true, null, null, null);
    }

    @Override
    public Optional<PullRequestState> pullRequestState(int number) {
        return Optional.empty();
    }

    void write(String path, String content) {
        Path file = resolve(path, true);
        try {
            Path parent = file.getParent();
            Files.createDirectories(parent);
            insideRoot(parent.toRealPath(), path); // ninguna carpeta intermedia es un enlace hacia fuera
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) rejectLink(file, path);
            Path tmp = Files.createTempFile(parent, ".kuador-sync-", ".tmp");
            try {
                Files.writeString(tmp, content, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir " + path, e);
        }
    }

    @Override
    public List<String> listMarkdown(String directory, int depth) {
        Path dir = resolve(directory, false);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (Stream<Path> walk = Files.walk(dir, depth)) { // sin FOLLOW_LINKS: no entra en enlaces
            return walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> SAFE_FILE.matcher(p).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo listar " + directory, e);
        }
    }

    private Path resolve(String relative, boolean file) {
        if (relative == null || relative.contains("..") || relative.startsWith("/") || relative.contains("//")
                || !(file ? SAFE_FILE : SAFE_DIR).matcher(relative).matches()) {
            throw new DomainException("Ruta no permitida: " + relative);
        }
        Path resolved = root.resolve(relative).normalize();
        insideRoot(resolved, relative);
        return resolved;
    }

    private void insideRoot(Path path, String relative) {
        if (!path.startsWith(root)) {
            throw new DomainException("Ruta fuera del repositorio: " + relative);
        }
    }

    private static void rejectLink(Path file, String relative) {
        if (Files.isSymbolicLink(file)) {
            throw new DomainException(relative + " es un enlace simbólico; no se sincroniza.");
        }
    }

}
