package ec.kuador.board.sync.infrastructure.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.sync.domain.RepositoryFiles;
import ec.kuador.board.sync.domain.RepositoryLink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import ec.kuador.board.sync.domain.RepositoryProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFolderFilesTest {

    @TempDir
    Path tmp;

    private Path allowed;
    private Path repo;
    private Path outside;
    private RepositoryFiles files;
    private LocalRepositoryAccess access;

    @BeforeEach
    void setUp() throws IOException {
        allowed = Files.createDirectories(tmp.resolve("workspace")).toRealPath();
        repo = Files.createDirectories(allowed.resolve("kuador-api"));
        outside = Files.createDirectories(tmp.resolve("secret")).toRealPath();
        Files.writeString(outside.resolve("passwd.md"), "secreto");
        access = new LocalRepositoryAccess(allowed.toString());
        files = access.open(new RepositoryLink("KUA", "LOCAL", repo.toString(), null, null, Instant.EPOCH, null));
    }

    @Test
    void readsAndWritesAtomicallyCreatingFolders() throws IOException {
        files.publish(List.of(new RepositoryFiles.FileWrite("specs/001-lead-capture/spec.md", "# Spec\nñandú ✓")), "m", "t", "d", null);

        assertThat(files.read("specs/001-lead-capture/spec.md")).contains("# Spec\nñandú ✓");
        try (var listing = Files.list(repo.resolve("specs/001-lead-capture"))) {
            assertThat(listing.map(p -> p.getFileName().toString())).containsExactly("spec.md"); // sin temporales
        }
        assertThat(files.read("PRODUCT.md")).isEmpty();
        assertThat(files.listMarkdown("specs", 2)).containsExactly("specs/001-lead-capture/spec.md");
    }

    @Test
    void rejectsPathsThatEscapeTheRepository() {
        for (String bad : new String[] {"../secret/passwd.md", "/etc/passwd.md", "specs/../../secret/passwd.md", "specs//x.md", "notas.txt", "a b.md"}) {
            assertThatThrownBy(() -> files.read(bad)).as(bad).isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> write(bad, "x")).as(bad).isInstanceOf(DomainException.class);
        }
    }

    @Test
    void refusesSymlinksPointingOutside() throws IOException {
        Files.createSymbolicLink(repo.resolve("AGENTS.md"), outside.resolve("passwd.md"));
        Files.createSymbolicLink(repo.resolve("docs"), outside);

        assertThatThrownBy(() -> files.read("AGENTS.md")).isInstanceOf(DomainException.class).hasMessageContaining("enlace");
        assertThatThrownBy(() -> write("AGENTS.md", "pisado")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> write("docs/architecture.md", "x")).isInstanceOf(DomainException.class).hasMessageContaining("fuera");
        assertThat(files.listMarkdown("docs", 1)).isEmpty();
        assertThat(Files.readString(outside.resolve("passwd.md"))).isEqualTo("secreto");
        assertThat(Files.exists(outside.resolve("architecture.md"))).isFalse();
    }

    @Test
    void onlyFoldersInsideAllowedRootsCanBeLinked() throws IOException {
        assertThat(validate(access, repo.toString())).isEqualTo(repo.toString());
        assertThatThrownBy(() -> validate(access, outside.toString())).hasMessageContaining("fuera de las raíces");
        assertThatThrownBy(() -> validate(access, "relativa/kuador")).hasMessageContaining("absoluta");
        assertThatThrownBy(() -> validate(access, allowed.resolve("no-existe").toString())).hasMessageContaining("No existe");
        Path sneaky = Files.createSymbolicLink(allowed.resolve("atajo"), outside);
        assertThatThrownBy(() -> validate(access, sneaky.toString())).as("enlace dentro de la raíz que apunta fuera")
                .hasMessageContaining("fuera de las raíces");
        assertThatThrownBy(() -> validate(new LocalRepositoryAccess(""), repo.toString())).hasMessageContaining("desactivada");
    }

    private void write(String path, String content) {
        files.publish(List.of(new RepositoryFiles.FileWrite(path, content)), "m", "t", "d", null);
    }

    private static String validate(LocalRepositoryAccess access, String location) {
        return access.validate(new RepositoryProvider.LinkSpec(location, null, null)).location();
    }
}
