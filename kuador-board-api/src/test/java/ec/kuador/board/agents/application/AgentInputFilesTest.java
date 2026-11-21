package ec.kuador.board.agents.application;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.attachment.AttachmentApi.AttachmentInfo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentInputFilesTest {

    static final UUID PERSON = UUID.randomUUID();
    static final UUID MARKETING = UUID.randomUUID();
    static final UUID DESIGNER = UUID.randomUUID();
    int minute = 0;

    AttachmentInfo file(String name, UUID by) {
        return new AttachmentInfo(UUID.randomUUID(), "TABK-2", name, "x", 100, by, Instant.parse("2026-11-20T23:00:00Z").plusSeconds(60L * minute++));
    }

    @Test
    void elPlanCorregidoNoQuedaTapadoPorLasImagenesDeLaEntregaAnterior() {
        List<AttachmentInfo> files = new ArrayList<>();
        files.add(file("captura.png", PERSON));
        files.add(file("brief.md", MARKETING));
        files.add(file("plan.md", MARKETING));
        for (int i = 0; i < 32; i++) files.add(file("banner" + i + ".png", DESIGNER));
        AttachmentInfo v2a = file("plan_v2.md", MARKETING);
        AttachmentInfo v2b = file("plan_v2.md", MARKETING);
        files.add(v2a);
        files.add(v2b);

        List<AttachmentInfo> order = BoardAgent.inputOrder(files, Set.of(MARKETING, DESIGNER));
        List<String> first = order.subList(0, 10).stream().map(AttachmentInfo::fileName).toList();

        assertThat(first.subList(0, 4)).as("lo de la persona, luego los textos del más nuevo al más viejo")
                .containsExactly("captura.png", "plan_v2.md", "plan.md", "brief.md");
        assertThat(order).as("del mismo nombre, solo el último").contains(v2b).doesNotContain(v2a);
        assertThat(first.subList(4, 10)).as("después, las imágenes más nuevas").containsExactly("banner31.png", "banner30.png", "banner29.png",
                "banner28.png", "banner27.png", "banner26.png");
    }
}
