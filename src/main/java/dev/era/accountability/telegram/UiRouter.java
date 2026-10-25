package dev.era.accountability.telegram;

import dev.era.accountability.domain.Deadline;
import dev.era.accountability.repo.AuditRepository;
import dev.era.accountability.repo.DeadlineRepository;
import dev.era.accountability.repo.ReminderRepository;
import dev.era.accountability.repo.UiDraftRepository;
import dev.era.accountability.telegram.UiAction.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Where a tap or an answer goes.
 *
 * Three kinds of thing arrive here: a button from a menu or a card, an answer to
 * a question the bot asked in plain text, and a command nobody uses any more
 * (kept as a fallback, see CommandRouter).
 *
 * Every action is idempotent, because a tap can arrive twice: Telegram retries,
 * and the poller replays an update if it dies mid-handling. Marking something
 * DONE twice is a no-op, and so is deleting something already deleted.
 */
@Component
public class UiRouter {

    private static final Logger log = LoggerFactory.getLogger(UiRouter.class);

    private final Screens screens;
    private final DeadlineFlow deadlineFlow;
    private final QuietFlow quietFlow;
    private final UiDraftRepository drafts;
    private final DeadlineRepository deadlines;
    private final ReminderRepository reminders;
    private final AuditRepository audit;
    private final Clock clock;

    public UiRouter(Screens screens, DeadlineFlow deadlineFlow, QuietFlow quietFlow,
                    UiDraftRepository drafts, DeadlineRepository deadlines,
                    ReminderRepository reminders, AuditRepository audit, Clock clock) {
        this.screens = screens;
        this.deadlineFlow = deadlineFlow;
        this.quietFlow = quietFlow;
        this.drafts = drafts;
        this.deadlines = deadlines;
        this.reminders = reminders;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Taps
    // ------------------------------------------------------------------

    /**
     * @param messageId the message the tapped button hangs under, so the answer
     *                  can rewrite it in place instead of growing the chat.
     */
    public UiAction tap(long chatId, String data, Long messageId) {
        String[] parts = (data == null ? "" : data).split(":");
        String verb = parts.length > 0 ? parts[0] : "";

        try {
            return switch (verb) {
                case Keyboards.MENU, "" -> screens.menu(chatId).inMode(Mode.REPLACE);
                case Keyboards.LIST      -> screens.list(chatId).inMode(Mode.REPLACE);
                case Keyboards.HELP      -> screens.help().inMode(Mode.REPLACE);
                case Keyboards.QUIET     -> quiet(chatId, parts, messageId);
                case Keyboards.NEW       -> create(chatId, parts, messageId);
                case Keyboards.EDIT      -> edit(chatId, parts, messageId);
                case Keyboards.VIEW      -> view(chatId, parts);
                case Keyboards.DONE      -> done(chatId, parts);
                case Keyboards.SNOOZE    -> snooze(chatId, parts, Duration.ofDays(1), "Pushed a day");
                case Keyboards.SNOOZE_HOUR -> snooze(chatId, parts, Duration.ofHours(1), "Pushed an hour");
                case Keyboards.REMOVE    -> askRemove(chatId, parts);
                case Keyboards.CONFIRM   -> confirmRemove(chatId, parts);
                case Keyboards.CANCEL    -> keep(chatId, parts);
                default -> {
                    log.warn("unknown callback data '{}'", data);
                    yield UiAction.toast("That button is from an older version.");
                }
            };
        } catch (RuntimeException e) {
            log.error("tap '{}' failed", data, e);
            return UiAction.toast("That did not work. The error has been logged.");
        }
    }

    private UiAction view(long chatId, String[] parts) {
        long id = id(parts);
        var card = screens.card(chatId, id);
        if (card.mode() == Mode.NOTHING) {
            return card;
        }
        return card.inMode(Mode.REPLACE);
    }

    private UiAction done(long chatId, String[] parts) {
        var d = resolve(chatId, parts);
        if (d.isEmpty()) {
            return UiAction.toast("That one is gone.");
        }
        // DONE rather than delete: it keeps a record of what actually got
        // finished, and makes a second tap a no-op instead of an error.
        reminders.cancelUnsentFor(d.get().id());
        boolean changed = deadlines.markDone(d.get().id(), chatId);
        if (changed) {
            audit.record("owner", "DONE", "deadline", d.get().id(), null);
        }
        return new UiAction(Mode.REPLACE,
                "Done: <b>%s</b>".formatted(Render.escape(d.get().name())),
                Keyboards.NONE, changed ? "Done" : "Already settled", false, null);
    }

    private UiAction snooze(long chatId, String[] parts, Duration by, String toast) {
        var d = resolve(chatId, parts);
        if (d.isEmpty()) {
            return UiAction.toast("That one is gone.");
        }
        deadlines.reschedule(d.get().id(), chatId, d.get().dueAt().plus(by));
        audit.record("owner", "SNOOZE", "deadline", d.get().id(), null);
        // Queued reminders were drawn against the old moment, so they are the
        // wrong shape now: drop them and let the tick redraw.
        reminders.cancelUnsentFor(d.get().id());
        var card = screens.card(chatId, d.get().id());
        return card.inMode(Mode.REPLACE).withToast(toast);
    }

    private UiAction askRemove(long chatId, String[] parts) {
        var d = resolve(chatId, parts);
        if (d.isEmpty()) {
            return UiAction.toast("That one is gone.");
        }
        return new UiAction(Mode.REPLACE,
                "Delete <b>%s</b>?".formatted(Render.escape(d.get().name())),
                Keyboards.confirmRemove(d.get().id()), null, false, null);
    }

    private UiAction confirmRemove(long chatId, String[] parts) {
        var d = resolve(chatId, parts);
        if (d.isEmpty()) {
            return UiAction.toast("That one is gone.");
        }
        String name = d.get().name();
        deadlines.remove(d.get().id(), chatId);
        audit.record("owner", "REMOVE", "deadline", d.get().id(), null);
        return new UiAction(Mode.REPLACE, "Removed <b>%s</b>".formatted(Render.escape(name)),
                Keyboards.NONE, "Removed", false, null);
    }

    private UiAction keep(long chatId, String[] parts) {
        var d = resolve(chatId, parts);
        if (d.isEmpty()) {
            return UiAction.toast("Kept.");
        }
        return screens.card(chatId, d.get().id()).inMode(Mode.REPLACE).withToast("Kept");
    }

    // ------------------------------------------------------------------
    // The flows
    // ------------------------------------------------------------------

    private UiAction create(long chatId, String[] parts, Long messageId) {
        if (parts.length == 1) {
            return deadlineFlow.start(chatId, messageId);
        }
        return switch (parts[1]) {
            case "d" -> deadlineFlow.onDate(chatId, LocalDate.parse(parts[2]));
            case "dx" -> deadlineFlow.askDate(chatId, messageId);
            case "t" -> deadlineFlow.onTime(chatId, DeadlineFlow.parseTime(parts[2]));
            case "tx" -> deadlineFlow.askTime(chatId, messageId);
            case "b" -> deadlineFlow.back(chatId, messageId);
            case "ok" -> deadlineFlow.create(chatId);
            case "x" -> deadlineFlow.cancel(chatId);
            default -> UiAction.toast("That button is from an older version.");
        };
    }

    private UiAction edit(long chatId, String[] parts, Long messageId) {
        if (parts.length == 1) {
            return UiAction.toast("Which one? Open it from Pending.");
        }
        if (parts.length == 2) {
            long id = id(parts);
            var d = deadlines.find(id, chatId);
            if (d.isEmpty()) {
                return UiAction.toast("That one is gone.");
            }
            return new UiAction(Mode.REPLACE, Render.card(d.get(), screens.zone(chatId), clock.instant())
                    + "\n\nWhat do you want to change?", Keyboards.editMenu(id), null, false, null);
        }
        return switch (parts[1]) {
            case "m" -> deadlineFlow.startMove(chatId, id(new String[]{"", parts[2]}));
            case "r" -> deadlineFlow.startRename(chatId, id(new String[]{"", parts[2]}), messageId);
            case "d" -> deadlineFlow.onDate(chatId, LocalDate.parse(parts[2]));
            case "dx" -> deadlineFlow.askDate(chatId, messageId);
            case "t" -> deadlineFlow.onTime(chatId, DeadlineFlow.parseTime(parts[2]));
            case "tx" -> deadlineFlow.askTime(chatId, messageId);
            case "b" -> deadlineFlow.back(chatId, messageId);
            case "x" -> deadlineFlow.cancel(chatId);
            default -> UiAction.toast("That button is from an older version.");
        };
    }

    private UiAction quiet(long chatId, String[] parts, Long messageId) {
        if (parts.length == 1) {
            return quietFlow.menu(chatId);
        }
        return switch (parts[1]) {
            case "p" -> quietFlow.preset(chatId, parts[2]);
            case "c" -> quietFlow.custom(chatId, messageId);
            case "off" -> quietFlow.off(chatId);
            case "x" -> quietFlow.menu(chatId);
            default -> UiAction.toast("That button is from an older version.");
        };
    }

    // ------------------------------------------------------------------
    // Typed answers
    // ------------------------------------------------------------------

    /**
     * A plain text message. Empty when nothing was waiting for an answer, which
     * is the caller's cue to treat it as a command instead.
     */
    public Optional<UiAction> text(long chatId, String text) {
        var draft = drafts.find(chatId).orElse(null);
        if (draft == null || !draft.expectsText()) {
            return Optional.empty();
        }
        var answered = quietFlow.onText(chatId, text);
        if (answered.isPresent()) {
            return answered;
        }
        return deadlineFlow.onText(chatId, text);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private long id(String[] parts) {
        try {
            return Long.parseLong(parts.length > 1 ? parts[1].replace("#", "") : "");
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private Optional<Deadline> resolve(long chatId, String[] parts) {
        long id = id(parts);
        return id == 0 ? Optional.empty() : deadlines.find(id, chatId);
    }
}
