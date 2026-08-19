package dev.era.accountability.service;

import dev.era.accountability.config.ReminderProperties;
import dev.era.accountability.domain.CheckIn;
import dev.era.accountability.domain.Commitment;
import dev.era.accountability.domain.UserSettings;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The non-homogeneous Poisson sampler from NOTES.md section 4.
 *
 * A fixed schedule is a calendar app, and a fixed schedule dies to habituation:
 * a 09:00 ping is wallpaper inside a week because you can dismiss it before it
 * arrives. Unpredictable timing keeps working for the same reason variable-ratio
 * reinforcement outperforms fixed-ratio.
 *
 * The rate rises as the deadline approaches:
 *
 * <pre>
 *   lambda(t)  = clamp(A / days_remaining, lambda_min, lambda_max)
 *   next       = now + Exponential(lambda)
 * </pre>
 *
 * "Days remaining" means different things for the two commitment types, which is
 * the one thing section 4.1 does not spell out. For a DEADLINE it is literally
 * days until the due date. For a HABIT the deadline is always the end of today,
 * so days_remaining would pin to zero and lambda to its ceiling forever; the
 * same curve is therefore run over the fraction of the local day still left,
 * with a much smaller A. A habit escalates through its day; a deadline escalates
 * across its weeks.
 */
@Service
public class ReminderSampler {

    private final ReminderProperties props;

    public ReminderSampler(ReminderProperties props) {
        this.props = props;
    }

    /** Reminders per day at this moment. Exposed for tests and for logging. */
    public double rateFor(Commitment c, Instant now, Instant deadline) {
        double daysRemaining = (double) Duration.between(now, deadline).toMinutes() / (60.0 * 24.0);
        if (daysRemaining <= 0) {
            return props.lambdaMax();
        }
        double base = c.isHabit() ? props.habitLambdaBase() : props.deadlineLambdaBase();
        return clamp(base / daysRemaining, props.lambdaMin(), props.lambdaMax());
    }

    /**
     * Draws the next fire time, or empty if nothing more should be scheduled for
     * this day. The guardrails from NOTES.md section 4.2 are applied here rather
     * than at send time, because a reminder that is going to be suppressed is
     * better never scheduled: a queued reminder still counts against the caps.
     *
     * @param from      earliest acceptable time, usually now or the last plan
     * @param deadline  when the day or the deadline closes
     * @param lastForUser latest time already promised to this person, if any
     */
    public Optional<Instant> sampleNext(Commitment c,
                                        UserSettings settings,
                                        Instant from,
                                        Instant deadline,
                                        Optional<Instant> lastForUser) {
        if (!settings.quietHoursSet()) {
            // Not chosen yet means send nothing. Guessing a waking window is how
            // a bot earns a permanent mute at 04:00.
            return Optional.empty();
        }
        if (!from.isBefore(deadline)) {
            return Optional.empty();
        }

        Instant earliest = from;
        if (lastForUser.isPresent()) {
            Instant spaced = lastForUser.get().plus(Duration.ofMinutes(props.minSpacingMinutes()));
            if (spaced.isAfter(earliest)) {
                earliest = spaced;
            }
        }
        if (!earliest.isBefore(deadline)) {
            return Optional.empty();
        }

        double lambda = rateFor(c, earliest, deadline);
        Instant candidate = earliest.plus(exponentialDelay(lambda));

        // Walk out of quiet hours rather than resampling blindly, which would
        // loop forever when quiet hours cover the rest of the day.
        candidate = pushPastQuietHours(candidate, settings, deadline);

        if (candidate == null || !candidate.isBefore(deadline)) {
            return Optional.empty();
        }
        return Optional.of(candidate);
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
    private Instant pushPastQuietHours(Instant candidate, UserSettings settings, Instant deadline) {
        var zone = settings.zone();
        // At most a couple of hops: one to clear tonight, one for a wrap past
        // midnight. The bound is what stops a pathological config from spinning.
        for (int hop = 0; hop < 3; hop++) {
            ZonedDateTime local = candidate.atZone(zone);
            if (!settings.isQuiet(local.toLocalTime())) {
                return candidate;
            }
            candidate = nextQuietEnd(local, settings.quietHoursEnd()).toInstant();
            if (!candidate.isBefore(deadline)) {
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

    /**
     * Deadline for a check-in: its own due_at, which for a habit is midnight at
     * the start of the following local day.
     */
    public static Instant deadlineFor(CheckIn ci) {
        return ci.dueAt();
    }

    /** Start of a local day, used as the lower bound for per-day counting. */
    public static Instant startOfDay(LocalDate day, UserSettings settings) {
        return day.atStartOfDay(settings.zone()).toInstant();
    }
}
