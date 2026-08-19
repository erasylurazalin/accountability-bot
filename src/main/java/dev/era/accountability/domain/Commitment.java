package dev.era.accountability.domain;

import java.time.Instant;

/**
 * A thing you have committed to. Deliberately thin: timezone and quiet hours
 * belong to the person and live in {@link UserSettings}, and nothing here
 * encodes reminder cadence, because cadence is sampled rather than configured
 * (NOTES.md section 4).
 */
public record Commitment(
        long id,
        long chatId,
        String name,
        String type,
        boolean active,
        Instant createdAt
) {
    public static final String HABIT    = "HABIT";
    public static final String DEADLINE = "DEADLINE";

    public boolean isHabit() {
        return HABIT.equals(type);
    }
}
