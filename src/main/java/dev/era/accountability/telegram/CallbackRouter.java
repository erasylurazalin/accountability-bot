package dev.era.accountability.telegram;

import dev.era.accountability.domain.Deadline;
import dev.era.accountability.repo.AuditRepository;
import dev.era.accountability.repo.DeadlineRepository;
import dev.era.accountability.repo.ReminderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Handles button taps.
 *
 * Every action is idempotent, because a tap can arrive twice: Telegram retries,
 * and the poller replays an update if it dies mid-handling. Marking something
 * DONE twice is a no-op, and so is deleting something already deleted.
 */
@Component
public class CallbackRouter {

    private static final Logger log = LoggerFactory.getLogger(CallbackRouter.class);

    /** What a tap produced: new text for the message, buttons, and a toast. */
    public record Result(String text, Object keyboard, String toast) {}

    private final DeadlineRepository deadlines;
    private final ReminderRepository reminders;
    private final AuditRepository audit;
    private final CommandRouter commands;

    public CallbackRouter(DeadlineRepository deadlines,
                          ReminderRepository reminders,
                          AuditRepository audit,
                          CommandRouter commands) {
        this.deadlines = deadlines;
        this.reminders = reminders;
        this.audit = audit;
        this.commands = commands;
    }

    public Result handle(long chatId, String data) {
        String[] parts = (data == null ? "" : data).split(":", 2);
        String verb = parts[0];
        Optional<Deadline> d = parts.length > 1 ? resolve(chatId, parts[1]) : Optional.empty();

        try {
            return switch (verb) {
                case Keyboards.DONE    -> done(chatId, d);
                case Keyboards.SNOOZE  -> snooze(chatId, d);
                case Keyboards.REMOVE  -> askRemove(d);
                case Keyboards.CONFIRM -> confirmRemove(chatId, d);
                case Keyboards.CANCEL  -> new Result(null, null, "Kept.");
                default -> {
                    log.warn("unknown callback data '{}'", data);
                    yield new Result(null, null, "That button is from an older version.");
                }
            };
        } catch (RuntimeException e) {
            log.error("callback '{}' failed", data, e);
            return new Result(null, null, "That didn't work. The error has been logged.");
        }
    }

    private Result done(long chatId, Optional<Deadline> d) {
        if (d.isEmpty()) {
            return gone();
        }
        // Marking DONE rather than deleting: this is the one place the status
        // is reachable, and it keeps a record of what actually got finished.
        reminders.cancelUnsentFor(d.get().id());
        boolean changed = deadlines.markDone(d.get().id(), chatId);
        if (changed) {
            audit.record("owner", "DONE", "deadline", d.get().id(), null);
        }
        return new Result(
                "Done: <b>%s</b>".formatted(TelegramApi.escape(d.get().name())),
                null,
                changed ? "Done" : "Already settled");
    }

    private Result snooze(long chatId, Optional<Deadline> d) {
        if (d.isEmpty()) {
            return gone();
        }
        var moved = d.get().dueAt().plus(Duration.ofDays(1));
        deadlines.reschedule(d.get().id(), chatId, moved);
        audit.record("owner", "SNOOZE", "deadline", d.get().id(), null);
        // Queued reminders were drawn against the old deadline, so they are
        // now the wrong shape: drop them and let the tick redraw.
        reminders.cancelUnsentFor(d.get().id());
        return new Result(commands.describe(chatId, d.get().id()),
                          Keyboards.forDeadline(d.get().id()),
                          "Pushed a day");
    }

    private Result askRemove(Optional<Deadline> d) {
        if (d.isEmpty()) {
            return gone();
        }
        return new Result("Delete <b>%s</b>?".formatted(TelegramApi.escape(d.get().name())),
                          Keyboards.confirmRemove(d.get().id()),
                          null);
    }

    private Result confirmRemove(long chatId, Optional<Deadline> d) {
        if (d.isEmpty()) {
            return gone();
        }
        String name = d.get().name();
        deadlines.remove(d.get().id(), chatId);
        audit.record("owner", "REMOVE", "deadline", d.get().id(), null);
        return new Result("Removed <b>%s</b>".formatted(TelegramApi.escape(name)), null, "Removed");
    }

    private static Result gone() {
        return new Result("That one is gone.", null, "Not found");
    }

    private Optional<Deadline> resolve(long chatId, String rawId) {
        try {
            return deadlines.find(Long.parseLong(rawId.strip()), chatId);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
