package dev.era.accountability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the non-homogeneous Poisson sampler.
 *
 * These live in configuration rather than on the deadline row on purpose: there
 * is one user, the shape of the curve is the design, and the numbers are a
 * preference that only living with the bot will settle. /lambda overrides them
 * at runtime and stores the override in reminder_tuning.
 *
 * @param lambdaBase           A in lambda = A / days_remaining. 14 gives about
 *                             0.5 reminders/day 30 days out, 2/day at 7 days,
 *                             and hits the ceiling inside the last day.
 * @param lambdaMin            Floor, so a distant deadline still taps you.
 * @param lambdaMax            Ceiling, so an imminent one cannot spiral. Note
 *                             this binds well before the deadline, so it, not
 *                             lambdaBase, is the knob for the last day.
 * @param minSpacingMinutes    No two reminders closer than this, to anyone.
 * @param maxPerDeadlineDaily  Per-deadline daily cap.
 * @param maxPerUserDaily      Cap across every deadline: several sampling
 *                             independently will bury you, so the limit that
 *                             matters is on the person.
 * @param scheduleAheadMinutes How far ahead fire times are drawn and stored.
 *                             Short enough that removing a deadline does not
 *                             leave hours of queued nagging behind it.
 */
@ConfigurationProperties(prefix = "bot.reminder")
public record ReminderProperties(
        double lambdaBase,
        double lambdaMin,
        double lambdaMax,
        int minSpacingMinutes,
        int maxPerDeadlineDaily,
        int maxPerUserDaily,
        int scheduleAheadMinutes
) {
    public ReminderProperties {
        if (lambdaBase <= 0) lambdaBase = 14.0;
        if (lambdaMin <= 0) lambdaMin = 0.5;
        if (lambdaMax <= 0) lambdaMax = 10.0;
        if (lambdaMax < lambdaMin) lambdaMax = lambdaMin;
        if (minSpacingMinutes <= 0) minSpacingMinutes = 45;
        if (maxPerDeadlineDaily <= 0) maxPerDeadlineDaily = 6;
        if (maxPerUserDaily <= 0) maxPerUserDaily = 12;
        if (scheduleAheadMinutes <= 0) scheduleAheadMinutes = 180;
    }
}
