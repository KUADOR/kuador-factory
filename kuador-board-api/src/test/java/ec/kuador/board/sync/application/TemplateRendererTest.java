package ec.kuador.board.sync.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.catalog.ComponentTemplate;
import ec.kuador.board.sync.domain.RepositoryCreator.File;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateRendererTest {

    private static final ComponentTemplate API = new ComponentTemplate(null, "API_X", "API", "API de prueba", ComponentTemplate.Kind.BACKEND, "Java",
            "acme/plantilla-api", "v1", List.of(new ComponentTemplate.Variable("PAQUETE", "Paquete", "com.empresa.app")),
            new ComponentTemplate.Commands("mvn package", "mvn verify", "mvn run"), List.of("ci"), List.of(), List.of(), false, 1);

    private static final List<File> CODE = List.of(
            new File("src/{{PIEZA}}/App.java", "package {{PAQUETE}}; // {{PROYECTO}} {{NO_EXISTE}}"),
            new File("mvnw", "#!/bin/sh", false, true),
            new File("logo.png", "iVBORw0KGgo=", true, false),
            new File(".github/workflows/ci.yml", "working-directory: {{PIEZA_RUTA}}"),
            new File(".git/config", "nunca"));

    @Test
    void inAFolderVariablesAreReplacedAndWorkflowsMoveToTheRoot() {
        var values = TemplateRenderer.values(API, Map.of("PAQUETE", "ec.clinica.citas"), "Clínica", "CLI", "api", "api");
        List<File> out = TemplateRenderer.render(CODE, values, "api");
        assertThat(out).extracting(File::path).containsExactly("api/src/api/App.java", "api/mvnw", "api/logo.png", ".github/workflows/api-ci.yml");
        assertThat(out.get(0).content()).isEqualTo("package ec.clinica.citas; // Clínica {{NO_EXISTE}}");
        assertThat(out.get(1).executable()).isTrue();
        assertThat(out.get(2).content()).as("los binarios no se tocan").isEqualTo("iVBORw0KGgo=");
        assertThat(out.get(3).content()).isEqualTo("working-directory: api");
    }

    @Test
    void inItsOwnRepoEverythingStaysAtTheRootWithDefaults() {
        var values = TemplateRenderer.values(API, null, "Clínica", "CLI", "pagos", "");
        List<File> out = TemplateRenderer.render(CODE, values, "");
        assertThat(out).extracting(File::path).contains("src/pagos/App.java", ".github/workflows/ci.yml");
        assertThat(out.get(0).content()).startsWith("package com.empresa.app;");
        assertThat(out.get(3).content()).isEqualTo("working-directory: .");
    }

    @Test
    void rejectsUnknownVariablesMultilineValuesAndEscapingPaths() {
        assertThatThrownBy(() -> TemplateRenderer.values(API, Map.of("OTRA", "x"), "P", "P", "api", "api")).hasMessageContaining("OTRA");
        assertThatThrownBy(() -> TemplateRenderer.values(API, Map.of("PAQUETE", "a\nb"), "P", "P", "api", "api")).hasMessageContaining("una línea");
        var values = TemplateRenderer.values(API, Map.of("PAQUETE", ".."), "P", "P", "api", "api");
        assertThatThrownBy(() -> TemplateRenderer.render(List.of(new File("{{PAQUETE}}/x", "")), values, "api")).hasMessageContaining("no permitida");
    }
}
