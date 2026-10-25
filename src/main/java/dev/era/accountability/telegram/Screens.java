package dev.era.accountability.telegram;

import dev.era.accountability.domain.Deadline;
import dev.era.accountability.repo.DeadlineRepository;
import dev.era.accountability.repo.UserSettingsRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * The three screens the bot has: the menu, the pending list, and one deadline.
 *
 * Both a typed command and a tap land here, so /list and the Pending button
 * cannot drift apart. The caller decides whether the result replaces the tapped
 * message or arrives as a new one; see UiAction.inMode.
 */
@Component
public class Screens {

    /** How far back a lapsed deadline still gets a line in the list. */
    private static final Duration LAPSED_WINDOW = Duration.ofDays(7);

    private final DeadlineRepository deadlines;
    private final UserSettingsRepository userSettings;
    private final Clock clock;

    public Screens(DeadlineRepository deadlines, UserSettingsRepository userSettings, Clock clock) {
        this.deadlines = deadlines;
        this.userSettings = userSettings;
        this.clock = clock;
    }

    public UiAction menu(long chatId) {
        userSettings.ensureExists(chatId);
        Instant now = clock.instant();
        var pending = deadlines.findPendingByChat(chatId);
        Optional<Deadline> next = pending.stream().filter(d -> d.isPending()).findFirst();
        boolean quietSet = userSettings.find(chatId).map(s -> s.quietHoursSet()).orElse(false);

        return UiAction.send(
                Render.menu(pending.size(), next.orElse(null), quietSet, zone(chatId), now),
                Keyboards.menu());
    }

    public UiAction list(long chatId) {
        Instant now = clock.instant();
        List<Deadline> pending = deadlines.findPendingByChat(chatId);
        List<Deadline> lapsed = deadlines.findRecentlyExpired(chatId, now.minus(LAPSED_WINDOW));

        return UiAction.send(
                Render.list(pending, lapsed, zone(chatId), now),
                Keyboards.listRows(pending, lapsed, zone(chatId), now));
    }

    public UiAction card(long chatId, long id) {
        var d = deadlines.find(id, chatId);
        if (d.isEmpty()) {
            return UiAction.toast("That one is gone.");
        }
        String text = Render.card(d.get(), zone(chatId), clock.instant());
        return UiAction.send(text, Keyboards.card(id));
    }

    public UiAction help() {
        return UiAction.send(Render.help(), Keyboards.menu());
    }

    ZoneId zone(long chatId) {
        return userSettings.find(chatId).map(s -> s.zone()).orElse(ZoneId.of("Asia/Almaty"));
    }
}
