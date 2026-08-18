package dev.era.accountability.service;

import dev.era.accountability.domain.CheckIn;
import dev.era.accountability.domain.Commitment;
import dev.era.accountability.repo.CheckInRepository;
import dev.era.accountability.repo.CommitmentRepository;
import dev.era.accountability.repo.ReminderRepository;
import dev.era.accountability.telegram.TelegramApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * A single tick that recomputes what is due from the database, rather than a
 * set of individually scheduled timers.
 *
 * This is the answer to the scheduler question left open in NOTES.md section 9,
 * and restart-safety is why. In-memory timers are lost on restart and have to be
 * rebuilt correctly on boot; a tick that derives everything from persisted state
 * has no memory to lose. A restart costs at most one tick interval of latency,
 * which on a box that takes 20–40 s to start a JVM off a spinning disk is not
 * the dominant term anyway.
 *
 * See docs/adr/0001-tick-based-scheduler.md.
 */
@Service
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final CommitmentRepository commitments;
    private final CheckInRepository checkIns;
    private final ReminderRepository reminders;
    private final CheckInService checkInService;
    private final ReminderTextService textService;
    private final TelegramApi telegram;

    @Value("${bot.backfill-days:2}")
    private int backfillDays;

    /** How long a claim may sit unsent before the reaper retries it. */
    @Value("${bot.claim-retry-after-seconds:120}")
    private long claimRetryAfterSeconds;

    /** Past this age an unsent claim is dropped rather than delivered late. */
    @Value("${bot.claim-abandon-after-minutes:60}")
    private long claimAbandonAfterMinutes;

    public ReminderScheduler(CommitmentRepository commitments,
                             CheckInRepository checkIns,
                             ReminderRepository reminders,
                             CheckInService checkInService,
                             ReminderTextService textService,
                             TelegramApi telegram) {
        this.commitments = commitments;
        this.checkIns = checkIns;
        this.reminders = reminders;
        this.checkInService = checkInService;
        this.textService = textService;
        this.telegram = telegram;
    }

    @Scheduled(fixedDelayString = "${bot.tick-interval-ms:60000}", initialDelayString = "${bot.tick-initial-delay-ms:15000}")
    public void tick() {
        Instant now = Instant.now();
        try {
            var active = commitments.findActive();
            var byId = new HashMap<Long, Commitment>();
            for (Commitment c : active) {
                byId.put(c.id(), c);
                checkInService.materialiseWindows(c, now, backfillDays);
            }

            checkInService.expireClosedWindows(now);
            sendDueReminders(byId, now);
            recoverStaleClaims(now);
        } catch (RuntimeException e) {
            // A tick must never kill the scheduler; the next one retries from
            // persisted state, so a transient database or Telegram fault is
            // self-healing.
            log.error("tick failed", e);
        }
    }

    private void sendDueReminders(Map<Long, Commitment> byId, Instant now) {
        for (CheckIn ci : checkIns.findPendingInWindow(now)) {
            Commitment c = byId.get(ci.commitmentId());
            if (c == null) {
                continue;
            }

            var localTime = now.atZone(c.zone()).toLocalTime();
            if (c.isQuiet(localTime)) {
                continue;
            }

            // The slot is derived purely from the clock, so the same instant
            // always maps to the same idempotency key no matter how many times
            // the process restarts inside one interval.
            long minutesIn = Duration.between(ci.dueWindowStart(), now).toMinutes();
            long slot = minutesIn / Math.max(1, c.reminderIntervalMinutes());
            if (slot >= c.maxRemindersPerDay()) {
                continue;
            }

            int sentToday = reminders.countSentInWindow(c.id(), ci.dueWindowStart(), ci.dueWindowEnd());
            if (sentToday >= c.maxRemindersPerDay()) {
                continue;
            }

            String key = "c%d:%s:s%d".formatted(c.id(), ci.localDate(), slot);
            String body = textService.textFor(c, CheckInService.urgencyTier(ci, now));
            String message = "%s\n\n/done %d   /skip %d <why>".formatted(body, c.id(), c.id());

            // Claim first, then send. Losing the race here means another attempt
            // already owns this slot, so we stay silent rather than double-fire.
            var claimed = reminders.claim(c.id(), ci.id(), key, now, message);
            if (claimed.isEmpty()) {
                continue;
            }

            deliver(claimed.get(), c.chatId(), message);
        }
    }

    /**
     * Reclaims sends that were interrupted between the INSERT and the Telegram
     * call — the crash window that the claim-then-send ordering deliberately
     * trades a small delivery delay for. Old enough claims are dropped instead:
     * a reminder for a window that has moved on is noise.
     */
    private void recoverStaleClaims(Instant now) {
        Instant olderThan = now.minusSeconds(claimRetryAfterSeconds);
        Instant abandonBefore = now.minus(Duration.ofMinutes(claimAbandonAfterMinutes));

        for (var claim : reminders.findStaleClaims(olderThan, abandonBefore)) {
            log.info("retrying interrupted reminder claim {}", claim.id());
            deliver(claim.id(), claim.chatId(), claim.body());
        }

        int purged = reminders.purgeAbandonedClaims(abandonBefore);
        if (purged > 0) {
            log.info("purged {} abandoned reminder claim(s)", purged);
        }
    }

    private void deliver(long reminderEventId, long chatId, String message) {
        try {
            var messageId = telegram.sendMessage(chatId, message);
            if (messageId.isPresent()) {
                reminders.markSent(reminderEventId, messageId.get());
            }
            // A rejected send leaves sent_at NULL; the reaper picks it up.
        } catch (RuntimeException e) {
            log.warn("reminder {} could not be delivered, leaving claim for retry", reminderEventId, e);
        }
    }
}
