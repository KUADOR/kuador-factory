package ec.kuador.board.sync.application;

import ec.kuador.board.catalog.ComponentTemplate;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.sync.domain.RepositoryCreator.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convierte el código base de una plantilla en los archivos de una pieza: reemplaza {{VARIABLE}} en
 * contenidos de texto y rutas, y la ubica en su carpeta (monorepo) o en la raíz (un repo por pieza).
 *
 * <p>En un monorepo, los flujos de CI de la plantilla ({@code .github/workflows}) solo corren en la raíz:
 * se mueven allí con el nombre de la pieza delante. La plantilla los escribe con {{PIEZA_RUTA}} como
 * carpeta de trabajo para que sirvan igual en los dos casos.
 */
final class TemplateRenderer {

    static final String WORKFLOWS = ".github/workflows/";
    private static final Pattern TOKEN = Pattern.compile("\\{\\{([A-Z][A-Z0-9_]{0,39})}}");

    private TemplateRenderer() {}

    /** Valores finales: las del tablero, los defaults de la plantilla y lo que indicó la persona. */
    static Map<String, String> values(ComponentTemplate template, Map<String, String> given, String projectName, String projectKey, String piece, String folder) {
        Map<String, String> values = new LinkedHashMap<>();
        for (ComponentTemplate.Variable v : template == null ? List.<ComponentTemplate.Variable>of() : template.variables()) {
            values.put(v.name(), v.defaultValue() == null ? "" : v.defaultValue());
        }
        for (var e : (given == null ? Map.<String, String>of() : given).entrySet()) {
            if (!values.containsKey(e.getKey())) throw new DomainException("La plantilla no tiene la variable " + e.getKey() + ".");
            String v = e.getValue() == null ? "" : e.getValue().strip();
            if (v.length() > 200 || v.contains("\n")) throw new DomainException("El valor de " + e.getKey() + " va en una línea de hasta 200 caracteres.");
            values.put(e.getKey(), v);
        }
        values.put("PROYECTO", projectName);
        values.put("PROYECTO_CLAVE", projectKey);
        values.put("PIEZA", piece);
        values.put("PIEZA_RUTA", folder.isEmpty() ? "." : folder);
        return values;
    }

    static List<File> render(List<File> files, Map<String, String> values, String folder) {
        List<File> out = new ArrayList<>();
        for (File f : files) {
            if (f.path().startsWith(".git/")) continue;
            String path = replace(f.path(), values);
            if (path.contains("..") || path.startsWith("/")) throw new DomainException("Ruta no permitida en la plantilla: " + path);
            String content = f.base64() ? f.content() : replace(f.content(), values);
            String target;
            if (folder.isEmpty()) target = path;
            else if (path.startsWith(WORKFLOWS)) target = WORKFLOWS + folder + "-" + path.substring(WORKFLOWS.length()).replace('/', '-');
            else target = folder + "/" + path;
            out.add(new File(target, content, f.base64(), f.executable()));
        }
        return out;
    }

    private static String replace(String text, Map<String, String> values) {
        Matcher m = TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
