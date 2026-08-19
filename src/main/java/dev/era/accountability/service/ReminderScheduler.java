package dev.era.accountability.service;

import dev.era.accountability.config.ReminderProperties;
import dev.era.accountability.domain.CheckIn;
import dev.era.accountability.domain.Commitment;
import dev.era.accountability.domain.UserSettings;
import dev.era.accountability.repo.CheckInRepository;
import dev.era.accountability.repo.CommitmentRepository;
import dev.era.accountability.repo.ReminderRepository;
import dev.era.accountability.repo.UserSettingsRepository;
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
import java.util.Optional;

/**
 * A single tick that recomputes what is due from the database, rather than a set
 * of individually scheduled timers. See docs/adr/0001-tick-based-scheduler.md.
 *
 * Reminder timing is sampled, not fixed, so the tick has one more job than the
 * ADR describes: it plans ahead. A fire time is drawn and written down before it
 * is used, and the tick only ever delivers plans that already exist. That is
 * what makes a random schedule restart-safe. Recomputing the time after a crash
 * would draw a different number, and the idempotency key could not protect you
 * because it would be a different key.
 *
 * Order within a tick matters:
 *
 * <ol>
 *   <li>materialise today's check-ins, so there is something to attach to</li>
 *   <li>expire days that closed, and cancel anything queued for them</li>
 *   <li>plan reminders into the look-ahead window</li>
 *   <li>deliver plans whose time has come</li>
 *   <li>recover claims interrupted mid-send</li>
 * </ol>
 */
@Service
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final CommitmentRepository commitments;
    private final UserSettingsRepository userSettings;
    private final CheckInRepository checkIns;
    private final ReminderRepository reminders;
    private final CheckInService checkInService;
    private final ReminderTextService textService;
    private final ReminderSampler sampler;
    private final ReminderProperties reminderProps;
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
                             UserSettingsRepository userSettings,
                             CheckInRepository checkIns,
                             ReminderRepository reminders,
                             CheckInService checkInService,
                             ReminderTextService textService,
                             ReminderSampler sampler,
                             ReminderProperties reminderProps,
                             TelegramApi telegram) {
        this.commitments = commitments;
        this.userSettings = userSettings;
        this.checkIns = checkIns;
        this.reminders = reminders;
        this.checkInService = checkInService;
        this.textService = textService;
        this.sampler = sampler;
        this.reminderProps = reminderProps;
        this.telegram = telegram;
    }

    @Scheduled(fixedDelayString = "${bot.tick-interval-ms:60000}",
               initialDelayString = "${bot.tick-initial-delay-ms:15000}")
    public void tick() {
        Instant now = Instant.now();
        try {
            var byId = new HashMap<Long, Commitment>();
            var settingsByChat = new HashMap<Long, UserSettings>();

            for (Commitment c : commitments.findActive()) {
                var settings = settingsFor(c.chatId(), settingsByChat);
                byId.put(c.id(), c);
                checkInService.materialiseDays(c, settings, now, backfillDays);
            }

            expireAndCancel(now);
            planReminders(byId, settingsByChat, now);
            deliverDue(byId, settingsByChat, now);
            recoverStaleClaims(now);
        } catch (RuntimeException e) {
            // A tick must never kill the scheduler; the next one retries from
            // persisted state, so a transient database or Telegram fault is
            // self-healing.
            log.error("tick failed", e);
        }
    }

    private UserSettings settingsFor(long chatId, Map<Long, UserSettings> cache) {
        return cache.computeIfAbsent(chatId, id -> {
            userSettings.ensureExists(id);
            return userSettings.find(id).orElseThrow();
        });
    }

    /**
     * A day that closed is a recorded miss, and anything still queued for it is
     * noise. Cancelling first keeps the caps honest, since queued reminders
     * count toward them.
     */
    private void expireAndCancel(Instant now) {
        for (CheckIn ci : checkIns.findExpirable(now)) {
            reminders.cancelUnsentFor(ci.id());
        }
        checkInService.expireClosedWindows(now);
    }

    /**
     * Draws fire times into the look-ahead window and persists them. Every
     * guardrail from NOTES.md section 4.2 is applied here rather than at send
     * time: a reminder that would be suppressed is better never planned, because
     * a plan already occupies a slot in the daily cap.
     */
    private void planReminders(Map<Long, Commitment> byId,
                               Map<Long, UserSettings> settingsByChat,
                               Instant now) {
        Instant horizon = now.plus(Duration.ofMinutes(reminderProps.scheduleAheadMinutes()));

        for (CheckIn ci : checkIns.findOpenAt(now)) {
            Commitment c = byId.get(ci.commitmentId());
            if (c == null) {
                continue;
            }
            UserSettings settings = settingsFor(c.chatId(), settingsByChat);
            if (!settings.quietHoursSet()) {
                continue;
            }

            Instant dayStart = ReminderSampler.startOfDay(ci.localDate(), settings);
            Instant deadline = ci.dueAt();

            while (true) {
                if (reminders.countPlanned(c.id(), dayStart, deadline)
                        >= reminderProps.maxPerCommitmentPerDay()) {
                    break;
                }
                if (reminders.countPlannedForChat(c.chatId(), dayStart, deadline)
                        >= reminderProps.maxPerUserPerDay()) {
                    break;
                }

                Optional<Instant> lastForUser = reminders.lastPlannedForChat(c.chatId(), dayStart);
                Instant from = lastForUser.filter(now::isBefore).orElse(now);

                var next = sampler.sampleNext(c, settings, from, deadline, lastForUser);
                if (next.isEmpty() || next.get().isAfter(horizon)) {
                    break;
                }

                int seq = reminders.nextSeq(c.id(), ci.localDate());
                String key = "c%d:%s:n%d".formatted(c.id(), ci.localDate(), seq);
                if (reminders.schedule(c.id(), ci.id(), key, seq, next.get()).isEmpty()) {
                    // Another tick won this sequence number. Stop rather than
                    // spin; the next tick re-reads the state and continues.
                    break;
                }
                log.debug("planned reminder {} for {} at {}", key, c.name(), next.get());
            }
        }
    }

    private void deliverDue(Map<Long, Commitment> byId,
                            Map<Long, UserSettings> settingsByChat,
                            Instant now) {
        for (var due : reminders.findDue(now)) {
            Commitment c = byId.get(due.commitmentId());
            if (c == null) {
                continue;
            }
            UserSettings settings = settingsFor(c.chatId(), settingsByChat);

            // Quiet hours are checked again at send time. A plan drawn three
            // hours ago can be overtaken by the user changing them since.
            if (settings.isQuiet(now.atZone(settings.zone()).toLocalTime())) {
                continue;
            }

            // Re-read the day. A plan drawn hours ago is stale if the day was
            // settled or rolled over in the meantime.
            var checkIn = checkIns.findOpen(c.id());
            if (checkIn.isEmpty() || checkIn.get().id() != due.checkInId()) {
                continue;
            }

            int tier = CheckInService.urgencyTier(checkIn.get(), now, settings.zone());
            String body = textService.textFor(c, tier);
            String message = "%s\n\n/done %d   /skip %d <why>".formatted(body, c.id(), c.id());

            // Claim first, then send. Losing this race means another attempt
            // already owns the send, so we stay silent rather than double-fire.
            if (!reminders.claim(due.id(), message)) {
                continue;
            }
            deliver(due.id(), c.chatId(), message);
        }
    }

    /**
     * Retries sends interrupted between the claim and the Telegram call: the
     * crash window that claim-then-send deliberately accepts. Old enough claims
     * are dropped instead: a reminder for a day that has moved on is noise.
     */
    private void recoverStaleClaims(Instant now) {
        Instant olderThan = now.minusSeconds(claimRetryAfterSeconds);
        Instant abandonBefore = now.minus(Duration.ofMinutes(claimAbandonAfterMinutes));

        for (var claim : reminders.findStaleClaims(olderThan, abandonBefore)) {
            log.info("retrying interrupted reminder claim {}", claim.id());
            deliver(claim.id(), claim.chatId(), claim.body());
        }

        int purged = reminders.purgeAbandoned(abandonBefore);
        if (purged > 0) {
            log.info("purged {} abandoned reminder(s)", purged);
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
