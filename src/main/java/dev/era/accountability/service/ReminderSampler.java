package dev.era.accountability.service;

import dev.era.accountability.domain.UserSettings;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The non-homogeneous Poisson sampler.
 *
 * A fixed schedule is a calendar app, and a fixed schedule dies to habituation:
 * a 09:00 ping is wallpaper inside a week because you can dismiss it before it
 * arrives. Unpredictable timing keeps working for the same reason variable-ratio
 * reinforcement outperforms fixed-ratio.
 *
 * The rate rises as the deadline approaches:
 *
 * <pre>
 *   lambda(t) = clamp(base / days_remaining, min, max)
 *   next      = now + Exponential(lambda)
 * </pre>
 *
 * Note that the max clamp binds well before the deadline: with base 14 it is
 * already pinned about a day and a half out. Past that point raising the base
 * does nothing and the ceiling is the only knob that matters.
 */
@Service
public class ReminderSampler {

    private final ReminderTuning tuning;

    public ReminderSampler(ReminderTuning tuning) {
        this.tuning = tuning;
    }

    /** Reminders per day at this moment. */
    public double rateFor(Instant now, Instant dueAt) {
        double daysRemaining = (double) Duration.between(now, dueAt).toMinutes() / (60.0 * 24.0);
        if (daysRemaining <= 0) {
            return tuning.lambdaMax();
        }
        return clamp(tuning.lambdaBase() / daysRemaining, tuning.lambdaMin(), tuning.lambdaMax());
    }

    /** True while the rate is pinned at its ceiling, which /lambda reports. */
    public boolean atCeiling(Instant now, Instant dueAt) {
        return rateFor(now, dueAt) >= tuning.lambdaMax();
    }

    /**
     * Draws the next fire time, or empty if nothing more should be scheduled.
     * The guardrails are applied here rather than at send time, because a
     * reminder that is going to be suppressed is better never scheduled: a
     * queued reminder still counts against the caps.
     *
     * @param from        earliest acceptable time, usually now or the last plan
     * @param dueAt       when the deadline closes
     * @param lastForUser latest time already promised to this person, if any
     */
    public Optional<Instant> sampleNext(UserSettings settings,
                                        Instant from,
                                        Instant dueAt,
                                        Optional<Instant> lastForUser) {
        if (!settings.quietHoursSet()) {
            // Not chosen yet means send nothing. Guessing a waking window is how
            // a bot earns a permanent mute at 04:00.
            return Optional.empty();
        }
        if (!from.isBefore(dueAt)) {
            return Optional.empty();
        }

        Instant earliest = from;
        if (lastForUser.isPresent()) {
            Instant spaced = lastForUser.get().plus(Duration.ofMinutes(tuning.minSpacingMinutes()));
            if (spaced.isAfter(earliest)) {
                earliest = spaced;
            }
        }
        if (!earliest.isBefore(dueAt)) {
            return Optional.empty();
        }

        Instant candidate = earliest.plus(exponentialDelay(rateFor(earliest, dueAt)));

        // Walk out of quiet hours rather than resampling blindly, which would
        // loop forever when quiet hours cover the rest of the time available.
        candidate = pushPastQuietHours(candidate, settings, dueAt);

        if (candidate == null || !candidate.isBefore(dueAt)) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    /**
     * Draws a whole run of fire times without persisting any of them, for the
     * /simulate command.
     *
     * This is emphatically not the schedule. Timing is sampled, so every call
     * returns a different sequence, and the sequence that will actually fire is
     * the one written into reminder_event by the tick. What this is good for is
     * seeing the shape: how the gaps tighten as the deadline closes, and where
     * quiet hours bite. Judging whether the numbers are annoying enough is
     * otherwise a week-long experiment per guess.
     */
    public List<Instant> simulate(UserSettings settings, Instant from, Instant dueAt, int maxDraws) {
        var out = new ArrayList<Instant>();
        Optional<Instant> last = Optional.empty();
        Instant cursor = from;
        while (out.size() < maxDraws) {
            var next = sampleNext(settings, cursor, dueAt, last);
            if (next.isEmpty()) {
                break;
            }
            out.add(next.get());
            last = next;
            cursor = next.get();
        }
        return out;
    }

    /**
     * Exponential inter-arrival time for a rate expressed per day. Inverse
     * transform sampling: -ln(U)/lambda with U uniform on (0,1].
     */
    private Duration exponentialDelay(double lambdaPerDay) {
        double u = ThreadLocalRandom.current().nextDouble();
        if (u <= 0) {
            u = Double.MIN_NORMAL;
        }
        double days = -Math.log(u) / lambdaPerDay;
        long seconds = (long) Math.ceil(days * 24 * 60 * 60);
        return Duration.ofSeconds(Math.max(1, seconds));
    }

    /**
     * Moves a candidate forward to the end of quiet hours if it lands inside
     * them. Returns null if quiet hours swallow everything up to the deadline.
     */
    private Instant pushPastQuietHours(Instant candidate, UserSettings settings, Instant dueAt) {
        var zone = settings.zone();
        // At most a couple of hops: one to clear tonight, one for a wrap past
        // midnight. The bound is what stops a pathological config from spinning.
        for (int hop = 0; hop < 3; hop++) {
            ZonedDateTime local = candidate.atZone(zone);
            if (!settings.isQuiet(local.toLocalTime())) {
                return candidate;
            }
            candidate = nextQuietEnd(local, settings.quietHoursEnd()).toInstant();
            if (!candidate.isBefore(dueAt)) {
                return null;
            }
        }
        return null;
    }

    private static ZonedDateTime nextQuietEnd(ZonedDateTime local, LocalTime quietEnd) {
        ZonedDateTime sameDay = local.toLocalDate().atTime(quietEnd).atZone(local.getZone());
        return sameDay.isAfter(local) ? sameDay : sameDay.plusDays(1);
    }

    private static double clamp(double v, double min, double max) {
        return Math.min(Math.max(v, min), max);
    }
}
