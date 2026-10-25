package dev.era.accountability.telegram;

import dev.era.accountability.repo.AuditRepository;
import dev.era.accountability.repo.UiDraftRepository;
import dev.era.accountability.repo.UiDraftRepository.Draft;
import dev.era.accountability.repo.UiDraftRepository.Step;
import dev.era.accountability.repo.UserSettingsRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.util.Optional;

/**
 * Quiet hours, as a picker rather than two typed clock times.
 *
 * This is the switch that decides whether the bot sends anything at all, so it
 * is on the main menu and its state is stated on screen. Quiet hours are never
 * guessed: unset stays unset, and the menu says reminders are off until they are
 * chosen. The rules the picker enforces are the same ones the old /quiet had,
 * including "same time on both sides means use the off switch".
 */
@Component
public class QuietFlow {

    private final UserSettingsRepository userSettings;
    private final UiDraftRepository drafts;
    private final AuditRepository audit;
    private final Clock clock;

    public QuietFlow(UserSettingsRepository userSettings, UiDraftRepository drafts,
                     AuditRepository audit, Clock clock) {
        this.userSettings = userSettings;
        this.drafts = drafts;
        this.audit = audit;
        this.clock = clock;
    }

    public UiAction menu(long chatId) {
        userSettings.ensureExists(chatId);
        var settings = userSettings.find(chatId).orElseThrow();
        // Opening the picker abandons any text answer that was in flight, so a
        // stray "23:00 07:00" later cannot land as a surprise change.
        drafts.clear(chatId);
        return UiAction.replace(
                Render.quiet(settings.quietHoursStart(), settings.quietHoursEnd()),
                Keyboards.quiet(settings.quietHoursSet()));
    }

    /** One of the presets, encoded as "2300-0700" in callback_data. */
    public UiAction preset(long chatId, String window) {
        String[] parts = window.split("-", 2);
        if (parts.length != 2) {
            return UiAction.alert("That window could not be read.");
        }
        try {
            return apply(chatId, DeadlineFlow.parseTime(parts[0]), DeadlineFlow.parseTime(parts[1]));
        } catch (DateTimeException e) {
            return UiAction.alert("That window could not be read.");
        }
    }

    /** Asks for two times, stored as a draft so a restart mid-answer is survivable. */
    public UiAction custom(long chatId, Long messageId) {
        userSettings.ensureExists(chatId);
        var draft = new Draft(chatId, Step.QUIET_CUSTOM, null, null, null, null,
                messageId, clock.instant());
        drafts.save(draft);
        if (messageId == null) {
            return UiAction.send(Render.askCustomQuiet(), Keyboards.cancelOnly(Keyboards.QUIET));
        }
        return UiAction.replaceMessage(messageId, Render.askCustomQuiet(),
                Keyboards.cancelOnly(Keyboards.QUIET));
    }

    public UiAction off(long chatId) {
        userSettings.setQuietHours(chatId, null, null);
        audit.record("owner", "QUIET_OFF", "user_settings", chatId, null);
        drafts.clear(chatId);
        return UiAction.replace(
                Render.quiet(null, null) + "\n\nReminders are off until a window is chosen.",
                Keyboards.quiet(false));
    }

    /**
     * The typed answer. Returns empty when the message was not answering the
     * custom-hours question, so the caller can treat it as a command instead.
     */
    public Optional<UiAction> onText(long chatId, String text) {
        var draft = drafts.find(chatId).orElse(null);
        if (draft == null || draft.step() != Step.QUIET_CUSTOM) {
            return Optional.empty();
        }

        String[] parts = text.strip().split("\\s+");
        if (parts.length != 2) {
            return Optional.of(UiAction.alert("Send two times, from and to, for example 23:00 07:00."));
        }
        try {
            return Optional.of(apply(chatId, LocalTime.parse(parts[0]), LocalTime.parse(parts[1])));
        } catch (DateTimeException e) {
            return Optional.of(UiAction.alert("I could not read those times. Use 24-hour HH:MM."));
        }
    }

    private UiAction apply(long chatId, LocalTime start, LocalTime end) {
        if (start.equals(end)) {
            return UiAction.alert("Those are the same time. Use the off switch for no quiet hours.");
        }
        userSettings.setQuietHours(chatId, start, end);
        audit.record("owner", "QUIET_SET", "user_settings", chatId, null);
        drafts.clear(chatId);
        return UiAction.replace(
                Render.quiet(start, end) + "\n\nReminders are on.",
                Keyboards.quiet(true));
    }
}
