package ec.kuador.board.docs.domain;

import java.time.LocalDate;

/** Rellena la plantilla de un documento (definida en la metodología) con los datos del proyecto e issue. */
public final class DocumentTemplate {

    private DocumentTemplate() {}

    public static String render(String template, String projectName, String issueKey, String issueTitle, LocalDate today) {
        return template.replace("{project}", projectName)
                .replace("{key}", issueKey == null ? "" : issueKey)
                .replace("{title}", issueTitle == null ? "" : issueTitle)
                .replace("{date}", today.toString());
    }
}
