package ec.kuador.board.routine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.shared.error.DomainException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ScheduleTest {

    // Miércoles 18 de noviembre de 2026, 10:00 en Guayaquil (UTC-5).
    private static final Instant NOW = Instant.parse("2026-11-18T15:00:00Z");

    @Test
    void everyDayAtItsHourInItsTimezone() {
        Schedule s = Schedule.of("DAILY", null, null, "08:30", "America/Guayaquil");
        assertThat(s.next(NOW)).as("hoy ya pasó: mañana").isEqualTo(Instant.parse("2026-11-19T13:30:00Z"));
        assertThat(Schedule.of("DAILY", null, null, "18:00", "America/Guayaquil").next(NOW)).as("hoy todavía").isEqualTo(Instant.parse("2026-11-18T23:00:00Z"));
        assertThat(s.describe()).isEqualTo("Todos los días a las 08:30");
    }

    @Test
    void someDaysOfTheWeek() {
        Schedule s = Schedule.of("WEEKLY", "1,4", null, "08:00", "America/Guayaquil");
        assertThat(s.next(NOW)).as("el jueves").isEqualTo(Instant.parse("2026-11-19T13:00:00Z"));
        assertThat(s.next(Instant.parse("2026-11-19T13:00:00Z"))).as("después, el lunes").isEqualTo(Instant.parse("2026-11-23T13:00:00Z"));
        assertThat(s.describe()).isEqualTo("Cada lunes y jueves a las 08:00");
    }

    @Test
    void onceAMonthAndTheLastDayWhenTheMonthIsShorter() {
        Schedule s = Schedule.of("MONTHLY", null, 31, "09:00", "America/Guayaquil");
        assertThat(s.next(NOW)).isEqualTo(Instant.parse("2026-11-30T14:00:00Z"));
        assertThat(s.next(Instant.parse("2027-01-31T15:00:00Z"))).as("febrero no tiene 31: el 28").isEqualTo(Instant.parse("2027-02-28T14:00:00Z"));
    }

    @Test
    void invalidSchedulesSayWhy() {
        assertThatThrownBy(() -> Schedule.of("HOURLY", null, null, "08:00", null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Schedule.of("WEEKLY", "", null, "08:00", null)).hasMessageContaining("al menos un día");
        assertThatThrownBy(() -> Schedule.of("DAILY", null, null, "8am", null)).hasMessageContaining("08:30");
        assertThatThrownBy(() -> Schedule.of("DAILY", null, null, "08:00", "Marte/Olympus")).hasMessageContaining("Zona horaria");
    }
}
