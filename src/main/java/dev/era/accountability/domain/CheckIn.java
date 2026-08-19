package dev.era.accountability.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One day of one commitment. This table is the adherence history, which is the
 * actual product, so a miss is recorded rather than deleted.
 *
 * {@code dueAt} is the exclusive upper bound: midnight at the start of the next
 * local day.
 */
public record CheckIn(
        long id,
        long commitmentId,
        LocalDate localDate,
        Instant dueAt,
        String status,
        Instant completedAt,
        String excuseText
) {
    public static final String PENDING = "PENDING";
    public static final String DONE    = "DONE";
    public static final String SKIPPED = "SKIPPED";
    public static final String EXPIRED = "EXPIRED";
}
