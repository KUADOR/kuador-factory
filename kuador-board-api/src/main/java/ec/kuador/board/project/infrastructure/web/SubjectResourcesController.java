package ec.kuador.board.project.infrastructure.web;

import ec.kuador.board.catalog.Capability;
import ec.kuador.board.project.application.SubjectService;
import ec.kuador.board.shared.error.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lo que es del proyecto, pedido desde el proyecto (Organización → Proyectos): su repositorio, sus piezas, su
 * sincronización y sus documentos base. Se guardan por proyecto, así que la petición se atiende por uno de los tableros que
 * lo trabajan con eso en su flujo (con repositorio o con documentos), con el proyecto fijado: las mismas reglas, permisos y
 * respuestas que desde el tablero.
 */
@RestController
class SubjectResourcesController {

    private static final Pattern PATH = Pattern.compile("^/api/subjects/([^/]+)/(repository|components|sync|docs|drafting)(/.*)?$");

    private final SubjectService subjects;

    SubjectResourcesController(SubjectService subjects) {
        this.subjects = subjects;
    }

    @RequestMapping({"/api/subjects/{key}/repository", "/api/subjects/{key}/repository/**", "/api/subjects/{key}/components",
            "/api/subjects/{key}/components/**", "/api/subjects/{key}/sync", "/api/subjects/{key}/sync/**", "/api/subjects/{key}/docs",
            "/api/subjects/{key}/docs/**", "/api/subjects/{key}/drafting", "/api/subjects/{key}/drafting/**"})
    void forward(@PathVariable String key, HttpServletRequest request, HttpServletResponse response) throws Exception {
        Matcher m = PATH.matcher(request.getRequestURI().substring(request.getContextPath().length()));
        if (!m.matches()) throw new DomainException("Ruta del proyecto no reconocida.");
        String subject = key.toUpperCase(Locale.ROOT);
        String area = m.group(2);
        boolean documents = area.equals("docs") || area.equals("drafting");
        String board = subjects.hostBoard(subject, documents ? Capability.PROJECT_DOCS : Capability.REPOSITORY)
                .orElseThrow(() -> new DomainException(documents
                        ? "Ningún tablero trabaja este proyecto con sus documentos todavía: súmalo a uno que los use."
                        : "Ningún tablero trabaja este proyecto con código todavía: súmalo a uno con repositorio."));
        String query = "subject=" + URLEncoder.encode(subject, StandardCharsets.UTF_8) + (request.getQueryString() == null ? "" : "&" + request.getQueryString());
        String target = "/api/projects/" + board + "/" + area + (m.group(3) == null ? "" : m.group(3)) + "?" + query;
        request.getRequestDispatcher(target).forward(request, response);
    }
}
