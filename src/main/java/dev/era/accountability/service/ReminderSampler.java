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
     * The next fire time in a chain, or empty if the chain has ended before the
     * deadline. Only for callers that keep nothing, such as /simulate.
     */
    public Optional<Instant> sampleNext(UserSettings settings,
                                        Instant from,
                                        Instant dueAt,
                                        Optional<Instant> lastPlanned) {
        return draw(settings, from, dueAt, lastPlanned).filter(t -> t.isBefore(dueAt));
    }

    /**
     * One draw, returned even when it lands at or past the deadline.
     *
     * The tick persists every draw, including the ones it cannot use. A draw
     * that is thrown away gets redrawn on the next tick, and redrawing every
     * minute until one fits is a different process with a far higher rate: it
     * pinned a deadline a month out at the daily cap. A draw past the deadline
     * is never sent, since the deadline expires first and expiry cancels it,
     * but while it sits there it ends the chain.
     *
     * Empty only when there is nothing to draw from: quiet hours not chosen,
     * or no room left before the deadline.
     *
     * @param from        earliest acceptable time, usually now or the last plan
     * @param dueAt       when the deadline closes
     * @param lastPlanned latest time already planned in this chain, if any
     */
    public Optional<Instant> draw(UserSettings settings,
                                  Instant from,
                                  Instant dueAt,
                                  Optional<Instant> lastPlanned) {
        if (!settings.quietHoursSet()) {
            // Not chosen yet means send nothing. Guessing a waking window is how
            // a bot earns a permanent mute at 04:00.
            return Optional.empty();
        }

        Instant earliest = from;
        if (lastPlanned.isPresent()) {
            Instant spaced = lastPlanned.get().plus(Duration.ofMinutes(tuning.minSpacingMinutes()));
            if (spaced.isAfter(earliest)) {
                earliest = spaced;
            }
        }
        if (!earliest.isBefore(dueAt)) {
            return Optional.empty();
        }

        Instant candidate = earliest.plus(exponentialDelay(rateFor(earliest, dueAt)));

        // Walk out of quiet hours rather than resampling, which would be the
        // same redraw-until-it-fits mistake as above.
        return Optional.of(pushPastQuietHours(candidate, settings));
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
     * them. The result may be past the deadline, which ends the chain.
     */
    private Instant pushPastQuietHours(Instant candidate, UserSettings settings) {
        var zone = settings.zone();
        // At most a couple of hops: one to clear tonight, one for a wrap past
        // midnight. The bound is what stops a pathological config from spinning.
        // Should it ever run out, delivery checks quiet hours again anyway.
        for (int hop = 0; hop < 3; hop++) {
            ZonedDateTime local = candidate.atZone(zone);
            if (!settings.isQuiet(local.toLocalTime())) {
                return candidate;
            }
            candidate = nextQuietEnd(local, settings.quietHoursEnd()).toInstant();
        }
        return candidate;
    }

    private static ZonedDateTime nextQuietEnd(ZonedDateTime local, LocalTime quietEnd) {
        ZonedDateTime sameDay = local.toLocalDate().atTime(quietEnd).atZone(local.getZone());
        return sameDay.isAfter(local) ? sameDay : sameDay.plusDays(1);
    }

    private static double clamp(double v, double min, double max) {
        return Math.min(Math.max(v, min), max);
    }
}
