package dev.era.accountability.domain;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Per-person settings. Quiet hours are nullable together: null means the user
 * has not chosen yet, which is a real state and not a missing value.
 *
 * The scheduler treats "not chosen" as "send nothing". That is deliberate. The
 * alternative is to guess a waking window, and a wrong guess pings you at 04:00,
 * which is exactly the failure that makes people mute a bot forever.
 */
public record UserSettings(
        long chatId,
        String timezone,
        LocalTime quietHoursStart,
        LocalTime quietHoursEnd
) {
    public ZoneId zone() {
        return ZoneId.of(timezone);
    }

    public boolean quietHoursSet() {
        return quietHoursStart != null && quietHoursEnd != null;
    }

    /**
     * Quiet hours normally wrap midnight (23:00 to 08:00), so the comparison
     * flips depending on whether start is before or after end. Equal bounds mean
     * a zero-length quiet period rather than an all-day one, since the latter
     * would silently disable every reminder.
     */
    public boolean isQuiet(LocalTime localTime) {
        if (!quietHoursSet() || quietHoursStart.equals(quietHoursEnd)) {
            return false;
        }
        if (quietHoursStart.isBefore(quietHoursEnd)) {
            return !localTime.isBefore(quietHoursStart) && localTime.isBefore(quietHoursEnd);
        }
        return !localTime.isBefore(quietHoursStart) || localTime.isBefore(quietHoursEnd);
    }
}
