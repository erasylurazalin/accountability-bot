package dev.era.accountability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the non-homogeneous Poisson sampler in NOTES.md section 4.
 *
 * These live in configuration rather than on the commitment row on purpose. The
 * previous iteration put reminder cadence in the database with no command to
 * reach it, which is complexity that buys nothing. There is one user; the shape
 * of the curve is the design, and the numbers are a preference that only a week
 * of living with it will settle.
 *
 * @param deadlineLambdaBase   A in lambda = A / days_remaining, for DEADLINE.
 *                             14 reproduces the table in NOTES.md section 4.1:
 *                             30 days out gives ~0.5/day, 7 days ~2/day.
 * @param habitLambdaBase      A for HABIT, where "days remaining" is the
 *                             fraction of the local day left. Much smaller,
 *                             because the whole period is at most one day.
 * @param lambdaMin            Floor, so a distant deadline still taps you.
 * @param lambdaMax            Ceiling, so an imminent one cannot spiral.
 * @param minSpacingMinutes    No two reminders closer than this, to anyone.
 * @param maxPerCommitmentPerDay Per-task cap.
 * @param maxPerUserPerDay     Cap across every commitment. NOTES.md section 4.2
 *                             names this as the week-two bug: three commitments
 *                             sampling independently will bury you, so the limit
 *                             that matters is on the person, not the task.
 * @param scheduleAheadMinutes How far ahead fire times are sampled and stored.
 *                             Small enough that a commitment finished early
 *                             does not leave hours of queued nagging.
 */
@ConfigurationProperties(prefix = "bot.reminder")
public record ReminderProperties(
        double deadlineLambdaBase,
        double habitLambdaBase,
        double lambdaMin,
        double lambdaMax,
        int minSpacingMinutes,
        int maxPerCommitmentPerDay,
        int maxPerUserPerDay,
        int scheduleAheadMinutes
) {
    public ReminderProperties {
        if (deadlineLambdaBase <= 0) deadlineLambdaBase = 14.0;
        if (habitLambdaBase <= 0) habitLambdaBase = 0.7;
        if (lambdaMin <= 0) lambdaMin = 0.5;
        if (lambdaMax <= 0) lambdaMax = 10.0;
        if (lambdaMax < lambdaMin) lambdaMax = lambdaMin;
        if (minSpacingMinutes <= 0) minSpacingMinutes = 45;
        if (maxPerCommitmentPerDay <= 0) maxPerCommitmentPerDay = 6;
        if (maxPerUserPerDay <= 0) maxPerUserPerDay = 12;
        if (scheduleAheadMinutes <= 0) scheduleAheadMinutes = 180;
    }
}
