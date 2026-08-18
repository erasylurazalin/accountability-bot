package dev.era.accountability.domain;

import java.time.Instant;
import java.time.LocalDate;

public record CheckIn(
        long id,
        long commitmentId,
        LocalDate localDate,
        Instant dueWindowStart,
        Instant dueWindowEnd,
        String status,
        Instant completedAt,
        String excuseText
) {
    public static final String PENDING = "PENDING";
    public static final String DONE    = "DONE";
    public static final String SKIPPED = "SKIPPED";
    public static final String EXPIRED = "EXPIRED";
}
