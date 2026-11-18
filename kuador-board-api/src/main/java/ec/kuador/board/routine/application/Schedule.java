package ec.kuador.board.routine.application;

import ec.kuador.board.shared.error.DomainException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Cuándo corre una rutina: cada día, ciertos días de la semana o un día del mes, a una hora y en una zona horaria. */
public record Schedule(String frequency, Set<Integer> weekdays, Integer monthDay, LocalTime at, ZoneId zone) {

    public static final List<String> FREQUENCIES = List.of("DAILY", "WEEKLY", "MONTHLY");

    public static Schedule of(String frequency, String weekdays, Integer monthDay, String at, String timezone) {
        String f = frequency == null ? "" : frequency.strip().toUpperCase();
        if (!FREQUENCIES.contains(f)) throw new DomainException("Elige cada cuánto: todos los días, ciertos días de la semana o una vez al mes.");
        LocalTime time;
        try {
            time = LocalTime.parse(at == null ? "" : at.strip());
        } catch (RuntimeException e) {
            throw new DomainException("Escribe la hora como 08:30.");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone == null || timezone.isBlank() ? "America/Guayaquil" : timezone.strip());
        } catch (RuntimeException e) {
            throw new DomainException("Zona horaria desconocida: " + timezone + ".");
        }
        Set<Integer> days = new TreeSet<>();
        if (f.equals("WEEKLY")) {
            for (String d : (weekdays == null ? "" : weekdays).split(",")) {
                if (d.isBlank()) continue;
                int n = Integer.parseInt(d.strip());
                if (n < 1 || n > 7) throw new DomainException("Los días van del 1 (lunes) al 7 (domingo).");
                days.add(n);
            }
            if (days.isEmpty()) throw new DomainException("Elige al menos un día de la semana.");
        }
        if (f.equals("MONTHLY") && (monthDay == null || monthDay < 1 || monthDay > 31)) throw new DomainException("Elige el día del mes (1 a 31).");
        return new Schedule(f, days, f.equals("MONTHLY") ? monthDay : null, time.withSecond(0).withNano(0), zone);
    }

    public String weekdaysText() {
        return weekdays.isEmpty() ? null : String.join(",", weekdays.stream().map(String::valueOf).toList());
    }

    /** La próxima vez que toca, estrictamente después de {@code after}. */
    public Instant next(Instant after) {
        ZonedDateTime from = after.atZone(zone);
        LocalDate day = from.toLocalDate();
        for (int i = 0; i < 400; i++) {
            LocalDate d = day.plusDays(i);
            if (matches(d)) {
                ZonedDateTime candidate = d.atTime(at).atZone(zone);
                if (candidate.toInstant().isAfter(after)) return candidate.toInstant();
            }
        }
        throw new IllegalStateException("La rutina no tiene una próxima fecha.");
    }

    private boolean matches(LocalDate d) {
        return switch (frequency) {
            case "WEEKLY" -> weekdays.contains(d.getDayOfWeek().getValue());
            case "MONTHLY" -> d.getDayOfMonth() == Math.min(monthDay, d.lengthOfMonth());
            default -> true;
        };
    }

    /** En palabras, para la lista: «Lunes y jueves a las 08:30». */
    public String describe() {
        String[] names = {"lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"};
        String hour = " a las " + at;
        return switch (frequency) {
            case "WEEKLY" -> {
                List<String> d = weekdays.stream().map(n -> names[n - 1]).toList();
                String joined = d.size() == 1 ? d.getFirst() : String.join(", ", d.subList(0, d.size() - 1)) + " y " + d.getLast();
                yield "Cada " + joined + hour;
            }
            case "MONTHLY" -> "El día " + monthDay + " de cada mes" + hour;
            default -> "Todos los días" + hour;
        };
    }
}
