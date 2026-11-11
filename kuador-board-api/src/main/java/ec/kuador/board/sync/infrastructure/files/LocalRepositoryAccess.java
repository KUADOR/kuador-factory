package ec.kuador.board.sync.infrastructure.files;

import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.sync.domain.RepositoryFiles;
import ec.kuador.board.sync.domain.RepositoryLink;
import ec.kuador.board.sync.domain.RepositoryProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Carpetas locales. Solo se aceptan carpetas dentro de {@code board.sync.allowed-roots}: la API
 * escribe en disco, así que sin raíces configuradas la sincronización local queda desactivada.
 */
@Component
class LocalRepositoryAccess implements RepositoryProvider {

    private final List<Path> allowedRoots;

    LocalRepositoryAccess(@Value("${board.sync.allowed-roots:}") String allowedRoots) {
        this.allowedRoots = Arrays.stream(allowedRoots.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(LocalRepositoryAccess::realOrNull)
                .filter(p -> p != null)
                .toList();
    }

    @Override
    public String kind() {
        return "LOCAL";
    }

    @Override
    public Validated validate(LinkSpec spec) {
        return new Validated(checked(spec.location()).toString(), null, null);
    }

    @Override
    public RepositoryFiles open(RepositoryLink link) {
        // Se revalida en cada uso: la configuración o el disco pudieron cambiar desde que se vinculó.
        return new LocalFolderFiles(checked(link.location()));
    }

    private Path checked(String location) {
        if (allowedRoots.isEmpty()) {
            throw new DomainException("La sincronización con carpetas locales está desactivada: define BOARD_SYNC_ALLOWED_ROOTS en la API.");
        }
        if (location == null || location.isBlank()) {
            throw new DomainException("Indica la ruta absoluta de la carpeta del repositorio.");
        }
        Path path = Path.of(location.strip());
        if (!path.isAbsolute()) {
            throw new DomainException("La ruta debe ser absoluta, por ejemplo /Users/…/proyecto-api.");
        }
        Path real = realOrNull(path.toString());
        if (real == null || !Files.isDirectory(real)) {
            throw new DomainException("No existe la carpeta " + location + ".");
        }
        if (allowedRoots.stream().noneMatch(real::startsWith)) {
            throw new DomainException("La carpeta " + real + " está fuera de las raíces permitidas (" + allowedRoots + ").");
        }
        return real;
    }

    private static Path realOrNull(String path) {
        try {
            return Path.of(path).toRealPath();
        } catch (IOException e) {
            return null;
        }
    }
}
