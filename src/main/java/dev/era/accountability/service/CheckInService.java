package dev.era.accountability.service;

import dev.era.accountability.domain.CheckIn;
import dev.era.accountability.domain.Commitment;
import dev.era.accountability.domain.UserSettings;
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
import java.time.ZoneId;
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

    /**
     * When a day's commitment stops being possible: midnight at the start of the
     * next local day, exclusive.
     *
     * atStartOfDay is used rather than atTime(23,59) because midnight is the one
     * local time that always exists. A daylight-saving spring-forward deletes an
     * hour of local time, and any fixed time inside the deleted hour has to be
     * resolved by a rule; midnight has never been inside one for any zone in the
     * tz database. It also avoids a dead second between 23:59:59 and 00:00:00.
     */
    public static Instant dueAtFor(LocalDate localDate, ZoneId zone) {
        return localDate.plusDays(1).atStartOfDay(zone).toInstant();
    }

    /**
     * Creates the check-in rows for today and the preceding {@code backfillDays}.
     *
     * Backfilling is this project's answer to the missed-window question in
     * NOTES.md section 9. If the box was down for a day, that day still gets a
     * row, which the expiry pass immediately marks EXPIRED: so an outage shows
     * up in the history as a real miss rather than as a silent gap. The backfill
     * is bounded so a long outage cannot manufacture weeks of fake misses,
     * and floored at the commitment's creation date so a new commitment is
     * not born already in arrears.
     */
    @Transactional
    public void materialiseDays(Commitment c, UserSettings settings, Instant now, int backfillDays) {
        var zone = settings.zone();
        LocalDate today = now.atZone(zone).toLocalDate();
        // The backfill exists to turn an outage into recorded misses, but it
        // cannot tell an outage from a commitment that simply did not exist
        // yet. Without this floor every new commitment is born owing
        // backfillDays worth of failures it had no chance to meet.
        LocalDate firstDay = c.createdAt().atZone(zone).toLocalDate();
        for (int i = backfillDays; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            if (day.isBefore(firstDay)) {
                continue;
            }
            checkIns.ensureDay(c.id(), day, dueAtFor(day, zone));
        }
    }

    /**
     * An untouched window that has closed is a miss. Marking it EXPIRED rather
     * than deleting it is deliberate: the adherence history is the product.
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
            log.info("expired {} closed check-in day(s)", expired);
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
     * NOTES.md section 3: it is not validation for its own sake, it is the
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

    /**
     * How pointed the reminder copy should be: 0 with most of the day left,
     * 1 past halfway, 2 in the final quarter. Derived from time remaining rather
     * than from a reminder count, so a day with one late reminder still gets the
     * urgent wording.
     */
    public static int urgencyTier(CheckIn ci, Instant now, ZoneId zone) {
        Instant dayStart = ci.localDate().atStartOfDay(zone).toInstant();
        long total = Duration.between(dayStart, ci.dueAt()).toMinutes();
        if (total <= 0) return 2;
        long elapsed = Duration.between(dayStart, now).toMinutes();
        double fraction = (double) elapsed / total;
        if (fraction >= 0.75) return 2;
        if (fraction >= 0.5) return 1;
        return 0;
    }
}
