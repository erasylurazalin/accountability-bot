package dev.era.accountability.service;

import dev.era.accountability.config.ReminderProperties;
import dev.era.accountability.repo.ReminderRepository;
import dev.era.accountability.repo.ReminderTuningRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The sampler's numbers, as they are right now.
 *
 * {@link ReminderProperties} is bound once at boot, which is the right shape
 * for config and the wrong shape for a constant you are trying to find by
 * experiment. So this sits in front of it: an override from the database wins,
 * a null override falls through to the configured value.
 *
 * Overrides are persisted rather than held in memory, because the alternative
 * is a restart silently reverting your tuning, which you would then debug as a
 * bug in the sampler.
 *
 * The shape of the curve is not tunable here on purpose. These are its
 * coefficients, not its design.
 */
@Service
public class ReminderTuning {

    private static final Logger log = LoggerFactory.getLogger(ReminderTuning.class);

    private final ReminderProperties defaults;
    private final ReminderTuningRepository repo;
    private final ReminderRepository reminders;

    private volatile ReminderTuningRepository.Overrides overrides =
            ReminderTuningRepository.Overrides.none();

    public ReminderTuning(ReminderProperties defaults,
                          ReminderTuningRepository repo,
                          ReminderRepository reminders) {
        this.defaults = defaults;
        this.repo = repo;
        this.reminders = reminders;
    }

    /**
     * Re-reads the overrides. Called at the top of each tick, which costs one
     * single-row read a minute and means a value changed by hand in psql takes
     * effect without a restart too.
     */
    public void reload() {
        try {
            repo.load().ifPresent(o -> overrides = o);
        } catch (RuntimeException e) {
            // Keep running on the last known values. A tuning table that cannot
            // be read is not a reason to stop sending reminders.
            log.warn("could not reload reminder tuning, keeping current values", e);
        }
    }

    public void set(String key, Number value) {
        repo.set(key, value);
        reload();
        replan();
        log.info("tuning {} set to {}", key, value);
    }

    public void reset() {
        repo.reset();
        reload();
        replan();
        log.info("tuning reset to application.yml values");
    }

    /**
     * Queued draws can sit days ahead and were made with the old numbers, so
     * a change would otherwise take days to show. The next tick draws again.
     * That is not the redraw-until-it-fits mistake, because it is triggered by
     * a person changing the tuning, not by a draw being rejected. An edit made
     * by hand in psql does not do this.
     */
    private void replan() {
        int dropped = reminders.cancelAllUnsent();
        if (dropped > 0) {
            log.info("dropped {} queued reminder(s) drawn with the old tuning", dropped);
        }
    }

    public boolean isOverridden(String key) {
        return switch (key) {
            case "base"    -> overrides.lambdaBase() != null;
            case "min"     -> overrides.lambdaMin() != null;
            case "max"     -> overrides.lambdaMax() != null;
            case "spacing" -> overrides.minSpacingMinutes() != null;
            case "cap"     -> overrides.maxPerDeadlineDaily() != null;
            case "usercap" -> overrides.maxPerUserDaily() != null;
            case "ahead"   -> overrides.scheduleAheadMinutes() != null;
            default        -> false;
        };
    }

    public double lambdaBase() {
        return or(overrides.lambdaBase(), defaults.lambdaBase());
    }

    public double lambdaMin() {
        return or(overrides.lambdaMin(), defaults.lambdaMin());
    }

    /**
     * Clamped up to lambdaMin. The database constraint only catches the case
     * where both bounds are overridden; overriding one and inheriting the other
     * can still cross them, and an inverted range would pin the rate to the
     * floor forever.
     */
    public double lambdaMax() {
        return Math.max(or(overrides.lambdaMax(), defaults.lambdaMax()), lambdaMin());
    }

    public int minSpacingMinutes() {
        return or(overrides.minSpacingMinutes(), defaults.minSpacingMinutes());
    }

    public int maxPerDeadlineDaily() {
        return or(overrides.maxPerDeadlineDaily(), defaults.maxPerDeadlineDaily());
    }

    public int maxPerUserDaily() {
        return or(overrides.maxPerUserDaily(), defaults.maxPerUserDaily());
    }

    public int scheduleAheadMinutes() {
        return or(overrides.scheduleAheadMinutes(), defaults.scheduleAheadMinutes());
    }

    public double configured(String key) {
        return switch (key) {
            case "base"    -> defaults.lambdaBase();
            case "min"     -> defaults.lambdaMin();
            case "max"     -> defaults.lambdaMax();
            case "spacing" -> defaults.minSpacingMinutes();
            case "cap"     -> defaults.maxPerDeadlineDaily();
            case "usercap" -> defaults.maxPerUserDaily();
            case "ahead"   -> defaults.scheduleAheadMinutes();
            default        -> Double.NaN;
        };
    }

    public double current(String key) {
        return switch (key) {
            case "base"    -> lambdaBase();
            case "min"     -> lambdaMin();
            case "max"     -> lambdaMax();
            case "spacing" -> minSpacingMinutes();
            case "cap"     -> maxPerDeadlineDaily();
            case "usercap" -> maxPerUserDaily();
            case "ahead"   -> scheduleAheadMinutes();
            default        -> Double.NaN;
        };
    }

    private static double or(Double override, double fallback) {
        return Optional.ofNullable(override).orElse(fallback);
    }

    private static int or(Integer override, int fallback) {
        return Optional.ofNullable(override).orElse(fallback);
    }
}
