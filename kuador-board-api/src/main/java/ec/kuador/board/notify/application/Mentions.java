package ec.kuador.board.notify.application;

import ec.kuador.board.member.MemberView;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Las personas que nombra un texto con «@»: por su nombre completo sin espacios ni tildes (@diegoacosta) o por su
 * primer nombre (@diego) si nadie más lo comparte. Lo que está entre comillas de código no cuenta. La web aplica la
 * misma regla (lib/mentions.ts) para resaltar y sugerir.
 */
final class Mentions {

    private static final Pattern TOKEN = Pattern.compile("(?<![\\p{L}\\p{N}_.@])@([\\p{L}\\p{N}][\\p{L}\\p{N}._-]{0,39})");
    private static final Pattern CODE = Pattern.compile("```[\\s\\S]*?(```|$)|`[^`\\n]*`");

    private Mentions() {}

    static Set<UUID> of(String text, List<MemberView> people) {
        Set<UUID> found = new LinkedHashSet<>();
        if (text == null || text.indexOf('@') < 0) return found;
        Map<String, Long> firsts = people.stream().collect(Collectors.groupingBy(m -> first(m.name()), Collectors.counting()));
        Map<String, UUID> byFull = people.stream().collect(Collectors.toMap(m -> normalize(m.name()), MemberView::id, (a, b) -> a));
        Map<String, UUID> byFirst = people.stream().filter(m -> firsts.get(first(m.name())) == 1)
                .collect(Collectors.toMap(m -> first(m.name()), MemberView::id, (a, b) -> a));
        Matcher m = TOKEN.matcher(CODE.matcher(text).replaceAll(" "));
        while (m.find()) {
            String token = normalize(m.group(1).replaceAll("[._-]+$", ""));
            if (token.isEmpty()) continue;
            UUID id = byFull.getOrDefault(token, byFirst.get(token));
            if (id != null) found.add(id);
        }
        return found;
    }

    static String normalize(String s) {
        String plain = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String first(String name) {
        String n = name == null ? "" : name.strip();
        int space = n.indexOf(' ');
        return normalize(space < 0 ? n : n.substring(0, space));
    }
}
