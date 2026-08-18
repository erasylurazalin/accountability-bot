package dev.era.accountability.service;

import dev.era.accountability.domain.CheckIn;
import dev.era.accountability.domain.Commitment;
import dev.era.accountability.repo.AuditRepository;
import dev.era.accountability.repo.CheckInRepository;
import dev.era.accountability.repo.StreakRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Optional;

@Service
public class CheckInService {

    private static final Logger log = LoggerFactory.getLogger(CheckInService.class);

    private final CheckInRepository checkIns;
    private final StreakRepository streaks;
    private final AuditRepository audit;

    public CheckInService(CheckInRepository checkIns, StreakRepository streaks, AuditRepository audit) {
        this.checkIns = checkIns;
        this.streaks = streaks;
        this.audit = audit;
    }

    /** Absolute bounds of the due window for a commitment on a given local date. */
    public static Instant[] windowFor(Commitment c, LocalDate localDate) {
        var zone = c.zone();
        ZonedDateTime start = localDate.atTime(c.windowStart()).atZone(zone);
        ZonedDateTime end = localDate.atTime(c.windowEnd()).atZone(zone);
        // A window whose end is not after its start is read as crossing midnight.
        if (!end.isAfter(start)) {
            end = end.plusDays(1);
        }
        return new Instant[]{start.toInstant(), end.toInstant()};
    }

    /**
     * Creates the check-in rows for today and the preceding {@code backfillDays}.
     *
     * Backfilling is this project's answer to the missed-window question in
     * NOTES.md section 9. If the box was down for a day, that day still gets a
     * row, which the expiry pass immediately marks EXPIRED — so an outage shows
     * up in the history as a real miss rather than as a silent gap. The backfill
     * is bounded so a long outage cannot manufacture weeks of fake misses.
     */
    @Transactional
    public void materialiseWindows(Commitment c, Instant now, int backfillDays) {
        LocalDate today = now.atZone(c.zone()).toLocalDate();
        for (int i = backfillDays; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            var bounds = windowFor(c, day);
            checkIns.ensureWindow(c.id(), day, bounds[0], bounds[1]);
        }
    }

    /**
     * An untouched window that has closed is a miss. Marking it EXPIRED rather
     * than deleting it is deliberate — the adherence history is the product.
     */
    @Transactional
    public int expireClosedWindows(Instant now) {
        int expired = 0;
        for (CheckIn ci : checkIns.findExpirable(now)) {
            if (checkIns.markExpired(ci.id())) {
                streaks.breakStreak(ci.commitmentId());
                audit.record("system", "EXPIRE", "check_in", ci.id(), null);
                expired++;
            }
        }
        if (expired > 0) {
            log.info("expired {} closed check-in window(s)", expired);
        }
        return expired;
    }

    @Transactional
    public Optional<String> markDone(Commitment c) {
        var open = checkIns.findOpen(c.id());
        if (open.isEmpty()) {
            return Optional.empty();
        }
        var ci = open.get();
        if (!checkIns.markDone(ci.id())) {
            return Optional.empty();
        }
        streaks.recordSuccess(c.id(), ci.localDate());
        audit.record("owner", "DONE", "check_in", ci.id(), null);
        var streak = streaks.find(c.id()).map(s -> s.currentLen()).orElse(1);
        return Optional.of("Logged: %s. Streak: %d day%s."
                .formatted(c.name(), streak, streak == 1 ? "" : "s"));
    }

    /**
     * Skipping requires a written excuse. That is the friction mechanism from
     * NOTES.md section 3 — it is not validation for its own sake, it is the
     * whole point of the SKIPPED path, and the database enforces it too.
     */
    @Transactional
    public Optional<String> markSkipped(Commitment c, String excuse) {
        if (excuse == null || excuse.isBlank()) {
            return Optional.of("A skip needs a reason. Usage: /skip <id> <why>");
        }
        var open = checkIns.findOpen(c.id());
        if (open.isEmpty()) {
            return Optional.empty();
        }
        var ci = open.get();
        if (!checkIns.markSkipped(ci.id(), excuse.strip())) {
            return Optional.empty();
        }
        streaks.breakStreak(c.id());
        audit.record("owner", "SKIP", "check_in", ci.id(), null);
        return Optional.of("Skipped %s. Logged: \"%s\". Streak reset to 0."
                .formatted(c.name(), excuse.strip()));
    }

    /** 0 early in the window, 1 past halfway, 2 in the final quarter. */
    public static int urgencyTier(CheckIn ci, Instant now) {
        long total = Duration.between(ci.dueWindowStart(), ci.dueWindowEnd()).toMinutes();
        if (total <= 0) return 2;
        long elapsed = Duration.between(ci.dueWindowStart(), now).toMinutes();
        double fraction = (double) elapsed / total;
        if (fraction >= 0.75) return 2;
        if (fraction >= 0.5) return 1;
        return 0;
    }
}
