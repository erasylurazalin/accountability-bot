package dev.era.accountability.domain;

import java.time.LocalTime;
import java.time.ZoneId;

public record Commitment(
        long id,
        long chatId,
        String name,
        String type,
        boolean active,
        String timezone,
        LocalTime windowStart,
        LocalTime windowEnd,
        LocalTime quietHoursStart,
        LocalTime quietHoursEnd,
        int reminderIntervalMinutes,
        int maxRemindersPerDay
) {
    public ZoneId zone() {
        return ZoneId.of(timezone);
    }

    /**
     * Quiet hours normally wrap midnight (23:00 to 08:00), so the comparison
     * flips depending on whether start is before or after end.
     */
    public boolean isQuiet(LocalTime localTime) {
        if (quietHoursStart.equals(quietHoursEnd)) {
            return false;
        }
        if (quietHoursStart.isBefore(quietHoursEnd)) {
            return !localTime.isBefore(quietHoursStart) && localTime.isBefore(quietHoursEnd);
        }
        return !localTime.isBefore(quietHoursStart) || localTime.isBefore(quietHoursEnd);
    }
}
