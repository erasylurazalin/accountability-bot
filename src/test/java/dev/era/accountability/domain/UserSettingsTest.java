package dev.era.accountability.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class UserSettingsTest {

    private static UserSettings quiet(String start, String end) {
        return new UserSettings(1L, "Asia/Almaty", LocalTime.parse(start), LocalTime.parse(end));
    }

    private static LocalTime t(String hhmm) {
        return LocalTime.parse(hhmm);
    }

    @Test
    void wrappingWindowIsQuietOnBothSidesOfMidnight() {
        var s = quiet("23:00", "08:00");

        assertThat(s.isQuiet(t("23:30"))).isTrue();
        assertThat(s.isQuiet(t("00:00"))).isTrue();
        assertThat(s.isQuiet(t("02:00"))).isTrue();
        assertThat(s.isQuiet(t("07:59"))).isTrue();

        assertThat(s.isQuiet(t("12:00"))).isFalse();
        assertThat(s.isQuiet(t("22:59"))).isFalse();
    }

    @Test
    void nonWrappingWindowIsQuietOnlyInside() {
        var s = quiet("02:00", "10:00");

        assertThat(s.isQuiet(t("05:00"))).isTrue();

        assertThat(s.isQuiet(t("01:59"))).isFalse();
        assertThat(s.isQuiet(t("12:00"))).isFalse();
        assertThat(s.isQuiet(t("23:30"))).isFalse();
    }

    @Test
    void startIsInclusiveAndEndIsExclusive() {
        var wrapping = quiet("23:00", "08:00");
        assertThat(wrapping.isQuiet(t("23:00"))).isTrue();
        assertThat(wrapping.isQuiet(t("08:00"))).isFalse();

        var plain = quiet("02:00", "10:00");
        assertThat(plain.isQuiet(t("02:00"))).isTrue();
        assertThat(plain.isQuiet(t("10:00"))).isFalse();
    }

    @Test
    void equalBoundsMeanNoQuietHoursNotAllDay() {
        // All-day quiet would stop every reminder without any error.
        var s = quiet("09:00", "09:00");

        assertThat(s.isQuiet(t("09:00"))).isFalse();
        assertThat(s.isQuiet(t("03:00"))).isFalse();
        assertThat(s.isQuiet(t("21:00"))).isFalse();
    }

    @Test
    void unsetIsNotQuietHereBecauseTheSchedulerGatesItSeparately() {
        // Unset quiet hours mean "send nothing", but that rule lives in
        // ReminderSampler and ReminderScheduler via quietHoursSet(), not here.
        var s = new UserSettings(1L, "Asia/Almaty", null, null);

        assertThat(s.quietHoursSet()).isFalse();
        assertThat(s.isQuiet(t("03:00"))).isFalse();
    }
}
