package dev.era.accountability.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * A thing due at a moment. The entire domain model.
 */
public record Deadline(
        long id,
        long chatId,
        String name,
        Instant dueAt,
        String status,
        Instant createdAt
) {
    public static final String PENDING = "PENDING";
    public static final String DONE    = "DONE";
    public static final String EXPIRED = "EXPIRED";

    public boolean isPending() {
        return PENDING.equals(status);
    }

    /**
     * How far through its life this deadline is, from 0 at creation to 1 at
     * due. Used only to choose how pointed the reminder wording should be.
     *
     * Measuring against the deadline's own lifespan rather than against a fixed
     * number of days is what makes it behave for both a two-hour errand and a
     * three-week assignment.
     */
    public double elapsedFraction(Instant now) {
        long total = Duration.between(createdAt, dueAt).toMinutes();
        if (total <= 0) {
            return 1.0;
        }
        double f = (double) Duration.between(createdAt, now).toMinutes() / total;
        return Math.clamp(f, 0.0, 1.0);
    }

    /** 0 with most of the time left, 1 past halfway, 2 in the final quarter. */
    public int urgencyTier(Instant now) {
        double f = elapsedFraction(now);
        if (f >= 0.75) return 2;
        if (f >= 0.5) return 1;
        return 0;
    }
}
