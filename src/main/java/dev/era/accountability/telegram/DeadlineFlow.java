package dev.era.accountability.telegram;

import dev.era.accountability.domain.Deadline;
import dev.era.accountability.repo.AuditRepository;
import dev.era.accountability.repo.DeadlineRepository;
import dev.era.accountability.repo.ReminderRepository;
import dev.era.accountability.repo.UiDraftRepository;
import dev.era.accountability.repo.UiDraftRepository.Draft;
import dev.era.accountability.repo.UiDraftRepository.Step;
import dev.era.accountability.repo.UserSettingsRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Making a deadline, and moving or renaming one, without typing a command.
 *
 * Name first, because that is the only part the bot cannot guess; then date and
 * time as buttons, with a typed fallback for anything the buttons do not cover.
 * The draft in between lives in Postgres, so a deploy or a restart in the middle
 * does not lose it. See UiDraftRepository.
 *
 * Every method takes the message the step should rewrite, so the flow happens in
 * one message that changes shape rather than a column of prompts.
 */
@Component
public class DeadlineFlow {

    static final int MAX_NAME = 120;

    private final UiDraftRepository drafts;
    private final DeadlineRepository deadlines;
    private final ReminderRepository reminders;
    private final UserSettingsRepository userSettings;
    private final AuditRepository audit;
    private final Clock clock;

    public DeadlineFlow(UiDraftRepository drafts, DeadlineRepository deadlines,
                        ReminderRepository reminders, UserSettingsRepository userSettings,
                        AuditRepository audit, Clock clock) {
        this.drafts = drafts;
        this.deadlines = deadlines;
        this.reminders = reminders;
        this.userSettings = userSettings;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // New deadline
    // ------------------------------------------------------------------

    /** Asks for the name. The answer is the only thing in this flow that is typed. */
    public UiAction start(long chatId, Long messageId) {
        userSettings.ensureExists(chatId);
        var draft = new Draft(chatId, Step.NEW_NAME, null, null, null, null, messageId, clock.instant());
        drafts.save(draft);
        return prompt(draft, Render.askName(), Keyboards.cancelOnly(Keyboards.NEW));
    }

    /** The confirm tap. Validates again, because a draft can sit for half an hour. */
    public UiAction create(long chatId) {
        var draft = drafts.find(chatId).orElse(null);
        if (draft == null || draft.step() != Step.NEW_CONFIRM || draft.name() == null) {
            return UiAction.toast("That form has expired. Start again from the menu.");
        }

        Instant dueAt = dueAt(chatId, draft);
        if (!dueAt.isAfter(clock.instant())) {
            drafts.clear(chatId);
            return UiAction.send("That moment has already passed, so there is nothing to remind you "
                               + "about. Nothing was added.", Keyboards.toMenu());
        }

        long id = deadlines.create(chatId, draft.name(), dueAt);
        audit.record("owner", "CREATE", "deadline", id, null);
        drafts.clear(chatId);

        var created = deadlines.find(id, chatId).orElseThrow();
        String text = Render.card(created, zone(chatId), clock.instant());
        boolean quietSet = userSettings.find(chatId).map(s -> s.quietHoursSet()).orElse(false);
        if (!quietSet) {
            text = text + Render.quietWarning();
        }
        return UiAction.replace(text, Keyboards.card(id));
    }

    // ------------------------------------------------------------------
    // Editing an existing deadline
    // ------------------------------------------------------------------

    public UiAction startRename(long chatId, long id, Long messageId) {
        var d = deadlines.find(id, chatId);
        if (d.isEmpty()) {
            return UiAction.toast("That one is gone.");
        }
        var draft = new Draft(chatId, Step.EDIT_RENAME, id, null, null, null, messageId, clock.instant());
        drafts.save(draft);
        return prompt(draft, Render.askRename(d.get().name()), Keyboards.cancelOnly(Keyboards.EDIT));
    }

    public UiAction startMove(long chatId, long id) {
        var d = deadlines.find(id, chatId);
        if (d.isEmpty()) {
            return UiAction.toast("That one is gone.");
        }
        var draft = new Draft(chatId, Step.EDIT_MOVE_DATE, id, d.get().name(),
                null, null, null, clock.instant());
        drafts.save(draft);
        return UiAction.send(Render.askDate(d.get().name()),
                Keyboards.dates(zone(chatId), clock.instant(), Keyboards.EDIT));
    }

    // ------------------------------------------------------------------
    // Answers
    // ------------------------------------------------------------------

    /** A date tapped, for either a new deadline or a move. */
    public UiAction onDate(long chatId, LocalDate date) {
        var draft = drafts.find(chatId).orElse(null);
        if (draft == null) {
            return UiAction.toast("That form has expired. Start again from the menu.");
        }

        if (date.isBefore(LocalDate.ofInstant(clock.instant(), zone(chatId)))) {
            return UiAction.toast("That date is in the past.");
        }

        Step next = draft.step().name().startsWith("EDIT") ? Step.EDIT_MOVE_TIME : Step.NEW_TIME;
        var moved = draft.at(next).withDate(date);
        drafts.save(moved);
        return UiAction.replace(Render.askTime(draft.name(), date),
                Keyboards.times(verbFor(moved)));
    }

    /** Typos get a toast and stay on the same question rather than dumping you out. */
    public UiAction onDateText(long chatId, String text) {
        var draft = drafts.find(chatId).orElse(null);
        if (draft == null) {
            return UiAction.toast("That form has expired. Start again from the menu.");
        }
        LocalDate date;
        try {
            date = parseDate(text, zone(chatId));
        } catch (DateTimeException e) {
            return UiAction.alert("I could not read that date. Send it as YYYY-MM-DD, or as today or tomorrow.");
        }
        return onDate(chatId, date);
    }

    public UiAction onTime(long chatId, LocalTime time) {
        var draft = drafts.find(chatId).orElse(null);
        if (draft == null || draft.dueDate() == null) {
            return UiAction.toast("That form has expired. Start again from the menu.");
        }

        var filled = draft.withTime(time);
        Instant dueAt = dueAt(chatId, filled);

        if (filled.step() == Step.EDIT_MOVE_TIME) {
            return reschedule(chatId, filled, dueAt);
        }

        var confirmed = filled.at(Step.NEW_CONFIRM);
        drafts.save(confirmed);
        return UiAction.replace(Render.draftCard(filled.name(), dueAt, zone(chatId), clock.instant()),
                Keyboards.confirmDraft(Keyboards.NEW));
    }

    public UiAction onTimeText(long chatId, String text) {
        var draft = drafts.find(chatId).orElse(null);
        if (draft == null) {
            return UiAction.toast("That form has expired. Start again from the menu.");
        }
        LocalTime time;
        try {
            time = parseTime(text);
        } catch (DateTimeException e) {
            return UiAction.alert("I could not read that time. Send it as HH:MM, for example 18:30.");
        }
        return onTime(chatId, time);
    }

    /** "Another date" on the picker. */
    public UiAction askDate(long chatId, Long messageId) {
        var draft = drafts.find(chatId).orElse(null);
        if (draft == null) {
            return UiAction.toast("That form has expired. Start again from the menu.");
        }
        Step next = draft.step() == Step.EDIT_MOVE_DATE ? Step.EDIT_MOVE_DATE_TEXT : Step.NEW_DATE_TEXT;
        var prompted = draft.at(next).withPrompt(messageId);
        drafts.save(prompted);
        return prompt(prompted, Render.askDateTyped(draft.name()), Keyboards.cancelOnly(verbFor(draft)));
    }

    /** "Another time" on the picker. */
    public UiAction askTime(long chatId, Long messageId) {
        var draft = drafts.find(chatId).orElse(null);
        if (draft == null || draft.dueDate() == null) {
            return UiAction.toast("That form has expired. Start again from the menu.");
        }
        Step next = draft.step() == Step.EDIT_MOVE_TIME ? Step.EDIT_MOVE_TIME_TEXT : Step.NEW_TIME_TEXT;
        var prompted = draft.at(next).withPrompt(messageId);
        drafts.save(prompted);
        return prompt(prompted, Render.askTimeTyped(draft.name(), draft.dueDate()), Keyboards.cancelOnly(verbFor(draft)));
    }

    /** Back one step: from the confirm card to the times, from the times to the dates. */
    public UiAction back(long chatId, Long messageId) {
        var draft = drafts.find(chatId).orElse(null);
        if (draft == null) {
            return UiAction.toast("That form has expired. Start again from the menu.");
        }

        return switch (draft.step()) {
            case NEW_CONFIRM -> {
                var again = draft.at(Step.NEW_TIME).withPrompt(messageId);
                drafts.save(again);
                yield UiAction.replace(Render.askTime(draft.name(), draft.dueDate()),
                        Keyboards.times(Keyboards.NEW));
            }
            case NEW_TIME, NEW_TIME_TEXT -> {
                var again = draft.at(Step.NEW_DATE).withPrompt(messageId);
                drafts.save(again);
                yield UiAction.replace(Render.askDate(draft.name()),
                        Keyboards.dates(zone(chatId), clock.instant(), Keyboards.NEW));
            }
            case EDIT_MOVE_TIME, EDIT_MOVE_TIME_TEXT -> {
                var again = draft.at(Step.EDIT_MOVE_DATE).withPrompt(messageId);
                drafts.save(again);
                yield UiAction.replace(Render.askDate(draft.name()),
                        Keyboards.dates(zone(chatId), clock.instant(), Keyboards.EDIT));
            }
            default -> cancel(chatId);
        };
    }

    public UiAction cancel(long chatId) {
        drafts.clear(chatId);
        return UiAction.replace("Cancelled. Nothing was changed.", Keyboards.toMenu());
    }

    // ------------------------------------------------------------------
    // Text answers
    // ------------------------------------------------------------------

    /**
     * The name question, which is the only answer this flow really needs typed,
     * plus the typed date and time fallbacks. Returns empty when the message was
     * not an answer to anything, so the caller can treat it as a command.
     */
    public java.util.Optional<UiAction> onText(long chatId, String text) {
        var draft = drafts.find(chatId).orElse(null);
        if (draft == null || !draft.expectsText()) {
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(switch (draft.step()) {
            case NEW_NAME -> nameForNewDeadline(chatId, draft, text);
            case EDIT_RENAME -> rename(chatId, draft, text);
            case NEW_DATE_TEXT, EDIT_MOVE_DATE_TEXT -> onDateText(chatId, text);
            case NEW_TIME_TEXT, EDIT_MOVE_TIME_TEXT -> onTimeText(chatId, text);
            // Handled by QuietFlow, which owns that question.
            default -> UiAction.toast("That form has expired. Start again from the menu.");
        });
    }

    private UiAction nameForNewDeadline(long chatId, Draft draft, String text) {
        String name = clean(text);
        if (name.isEmpty()) {
            return UiAction.alert("Send a name, for example hand in essay.");
        }
        if (name.length() > MAX_NAME) {
            return UiAction.alert("That name is too long (" + MAX_NAME + " characters max).");
        }

        var named = draft.at(Step.NEW_DATE).withName(name).withPrompt(draft.promptMessageId());
        drafts.save(named);
        return prompt(named, Render.askDate(name),
                Keyboards.dates(zone(chatId), clock.instant(), Keyboards.NEW));
    }

    private UiAction rename(long chatId, Draft draft, String text) {
        String name = clean(text);
        if (name.isEmpty()) {
            return UiAction.alert("Send a name, for example renew passport.");
        }
        if (name.length() > MAX_NAME) {
            return UiAction.alert("That name is too long (" + MAX_NAME + " characters max).");
        }
        if (draft.targetId() == null || !deadlines.rename(draft.targetId(), chatId, name)) {
            drafts.clear(chatId);
            return UiAction.toast("That one is gone.");
        }
        audit.record("owner", "RENAME", "deadline", draft.targetId(), null);
        Long messageId = draft.promptMessageId();
        drafts.clear(chatId);
        return showCard(chatId, draft.targetId(), "Renamed", messageId);
    }

    private UiAction reschedule(long chatId, Draft draft, Instant dueAt) {
        if (!dueAt.isAfter(clock.instant())) {
            return UiAction.alert("That moment has already passed.");
        }
        if (draft.targetId() == null || !deadlines.reschedule(draft.targetId(), chatId, dueAt)) {
            drafts.clear(chatId);
            return UiAction.toast("That one is gone.");
        }
        audit.record("owner", "RESCHEDULE", "deadline", draft.targetId(), null);
        // Queued reminders were drawn against the old moment, so they are the
        // wrong shape now. Drop them and let the tick redraw.
        reminders.cancelUnsentFor(draft.targetId());
        drafts.clear(chatId);
        return showCard(chatId, draft.targetId(), "Moved", null);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private UiAction showCard(long chatId, long id, String toast, Long messageId) {
        var d = deadlines.find(id, chatId);
        if (d.isEmpty()) {
            return UiAction.toast("That one is gone.");
        }
        String text = Render.card(d.get(), zone(chatId), clock.instant());
        return new UiAction(UiAction.Mode.REPLACE, text, Keyboards.card(id), toast, false, messageId);
    }

    private UiAction prompt(Draft draft, String text, Object markup) {
        drafts.save(draft);
        if (draft.promptMessageId() == null) {
            return UiAction.send(text, markup);
        }
        return UiAction.replaceMessage(draft.promptMessageId(), text, markup);
    }

    /** Which callback family a draft belongs to: new deadlines or moves. */
    private static String verbFor(Draft draft) {
        return draft.step().name().startsWith("EDIT") ? Keyboards.EDIT : Keyboards.NEW;
    }

    private Instant dueAt(long chatId, Draft draft) {
        return draft.dueDate().atTime(draft.dueTime()).atZone(zone(chatId)).toInstant();
    }

    ZoneId zone(long chatId) {
        return userSettings.find(chatId).map(s -> s.zone()).orElse(ZoneId.of("Asia/Almaty"));
    }

    static LocalDate parseDate(String raw, ZoneId zone) {
        String text = clean(raw).toLowerCase();
        return switch (text) {
            case "today" -> LocalDate.now(zone);
            case "tomorrow" -> LocalDate.now(zone).plusDays(1);
            default -> LocalDate.parse(text);
        };
    }

    static LocalTime parseTime(String raw) {
        String text = clean(raw);
        // 2359 and 23:59 both work; the buttons send the compact form.
        if (text.matches("\\d{4}")) {
            text = text.substring(0, 2) + ":" + text.substring(2);
        }
        return LocalTime.parse(text);
    }

    private static String clean(String raw) {
        return raw == null ? "" : raw.strip();
    }
}
