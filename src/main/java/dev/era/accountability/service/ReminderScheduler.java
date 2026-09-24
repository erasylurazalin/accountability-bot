package dev.era.accountability.service;

import dev.era.accountability.domain.Deadline;
import dev.era.accountability.domain.UserSettings;
import dev.era.accountability.repo.DeadlineRepository;
import dev.era.accountability.repo.ReminderRepository;
import dev.era.accountability.repo.UserSettingsRepository;
import dev.era.accountability.telegram.Keyboards;
import dev.era.accountability.telegram.TelegramApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A single tick that recomputes what is due from the database, rather than a
 * set of individually scheduled timers. See docs/adr/0001-tick-based-scheduler.
 *
 * Reminder timing is sampled, not fixed, so the tick has one more job than the
 * ADR describes: it plans ahead. A fire time is drawn and written down before
 * it is used, and the tick only ever delivers plans that already exist. That is
 * what makes a random schedule restart-safe. Recomputing the time after a crash
 * would draw a different number, and the idempotency key could not protect you
 * because it would be a different key.
 *
 * Order within a tick matters:
 *
 * <ol>
 *   <li>expire deadlines that lapsed, and cancel anything queued for them</li>
 *   <li>plan reminders into the look-ahead window</li>
 *   <li>deliver plans whose time has come</li>
 *   <li>recover claims interrupted mid-send</li>
 * </ol>
 */
@Service
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final DeadlineRepository deadlines;
    private final UserSettingsRepository userSettings;
    private final ReminderRepository reminders;
    private final ReminderSampler sampler;
    private final ReminderTuning tuning;
    private final TelegramApi telegram;

    /** How long a claim may sit unsent before the reaper retries it. */
    @Value("${bot.claim-retry-after-seconds:120}")
    private long claimRetryAfterSeconds;

    /** Past this age an unsent claim is dropped rather than delivered late. */
    @Value("${bot.claim-abandon-after-minutes:60}")
    private long claimAbandonAfterMinutes;

    public ReminderScheduler(DeadlineRepository deadlines,
                             UserSettingsRepository userSettings,
                             ReminderRepository reminders,
                             ReminderSampler sampler,
                             ReminderTuning tuning,
                             TelegramApi telegram) {
        this.deadlines = deadlines;
        this.userSettings = userSettings;
        this.reminders = reminders;
        this.sampler = sampler;
        this.tuning = tuning;
        this.telegram = telegram;
    }

    @Scheduled(fixedDelayString = "${bot.tick-interval-ms:60000}",
               initialDelayString = "${bot.tick-initial-delay-ms:15000}")
    public void tick() {
        Instant now = Instant.now();
        try {
            // Tuning can be changed from Telegram mid-run, so it is re-read here
            // rather than captured at boot.
            tuning.reload();

            var settingsByChat = new HashMap<Long, UserSettings>();
            expireLapsed(now);
            planReminders(settingsByChat, now);
            deliverDue(settingsByChat, now);
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
     * A deadline whose moment has passed is EXPIRED, and anything still queued
     * for it is noise. Cancelling first keeps the caps honest, since queued
     * reminders count toward them.
     */
    private void expireLapsed(Instant now) {
        for (Deadline d : deadlines.findLapsed(now)) {
            reminders.cancelUnsentFor(d.id());
            if (deadlines.markExpired(d.id())) {
                log.info("deadline {} ({}) lapsed", d.id(), d.name());
            }
        }
    }

    /**
     * Draws fire times into the look-ahead window and persists them.
     *
     * The caps are per calendar day, counted over today in the user's zone.
     * Counting over anything anchored to the deadline itself would leave the
     * window in the future for a deadline days out, so the cap would never bind
     * and the first day would arrive as an unbroken wall of reminders.
     */
    private void planReminders(Map<Long, UserSettings> settingsByChat, Instant now) {
        Instant horizon = now.plus(Duration.ofMinutes(tuning.scheduleAheadMinutes()));

        for (Deadline d : deadlines.findPending()) {
            UserSettings settings = settingsFor(d.chatId(), settingsByChat);
            if (!settings.quietHoursSet()) {
                continue;
            }

            var zone = settings.zone();
            LocalDate today = now.atZone(zone).toLocalDate();
            Instant dayStart = today.atStartOfDay(zone).toInstant();
            Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();
            Instant capUntil = d.dueAt().isBefore(dayEnd) ? d.dueAt() : dayEnd;

            while (true) {
                if (reminders.countPlanned(d.id(), dayStart, capUntil) >= tuning.maxPerDeadlineDaily()) {
                    break;
                }
                if (reminders.countPlannedForChat(d.chatId(), dayStart, capUntil) >= tuning.maxPerUserDaily()) {
                    break;
                }

                Optional<Instant> lastForUser = reminders.lastPlannedForChat(d.chatId(), dayStart);
                Instant from = lastForUser.filter(now::isBefore).orElse(now);

                var next = sampler.sampleNext(settings, from, d.dueAt(), lastForUser);
                if (next.isEmpty() || next.get().isAfter(horizon)) {
                    break;
                }

                int seq = reminders.nextSeq(d.id());
                String key = "d%d:n%d".formatted(d.id(), seq);
                if (reminders.schedule(d.id(), key, seq, next.get()).isEmpty()) {
                    // Another tick won this sequence number. Stop rather than
                    // spin; the next tick re-reads the state and continues.
                    break;
                }
                log.debug("planned reminder {} for {} at {}", key, d.name(), next.get());
            }
        }
    }

    private void deliverDue(Map<Long, UserSettings> settingsByChat, Instant now) {
        for (var due : reminders.findDue(now)) {
            var deadline = deadlines.find(due.deadlineId(), due.chatId());
            if (deadline.isEmpty() || !deadline.get().isPending()) {
                continue;
            }
            Deadline d = deadline.get();
            UserSettings settings = settingsFor(d.chatId(), settingsByChat);

            // Quiet hours are checked again at send time. A plan drawn three
            // hours ago can be overtaken by the user changing them since.
            if (settings.isQuiet(now.atZone(settings.zone()).toLocalTime())) {
                continue;
            }

            String message = StaticTemplates.pick(d, now)
                    + "\n\n%s".formatted(StaticTemplates.when(d, settings.zone()));

            // Claim first, then send. Losing this race means another attempt
            // already owns the send, so we stay silent rather than double-fire.
            if (!reminders.claim(due.id(), message)) {
                continue;
            }
            deliver(due.id(), d.chatId(), message, Keyboards.forDeadline(d.id()));
        }
    }

    /**
     * Retries sends interrupted between the claim and the Telegram call: the
     * crash window that claim-then-send deliberately accepts. Old enough claims
     * are dropped instead, since a very late reminder is noise.
     */
    private void recoverStaleClaims(Instant now) {
        Instant olderThan = now.minusSeconds(claimRetryAfterSeconds);
        Instant abandonBefore = now.minus(Duration.ofMinutes(claimAbandonAfterMinutes));

        for (var claim : reminders.findStaleClaims(olderThan, abandonBefore)) {
            log.info("retrying interrupted reminder claim {}", claim.id());
            deliver(claim.id(), claim.chatId(), claim.body(),
                    Keyboards.forDeadline(claim.deadlineId()));
        }

        int purged = reminders.purgeAbandoned(abandonBefore);
        if (purged > 0) {
            log.info("purged {} abandoned reminder(s)", purged);
        }
    }

    private void deliver(long reminderEventId, long chatId, String message, Object keyboard) {
        try {
            var messageId = telegram.sendMessage(chatId, message, keyboard);
            if (messageId.isPresent()) {
                reminders.markSent(reminderEventId, messageId.get());
            }
            // A rejected send leaves sent_at NULL; the reaper picks it up.
        } catch (RuntimeException e) {
            log.warn("reminder {} could not be delivered, leaving claim for retry", reminderEventId, e);
        }
    }
}
