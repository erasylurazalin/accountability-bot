package dev.era.accountability.telegram;

import dev.era.accountability.domain.Deadline;
import dev.era.accountability.domain.UserSettings;
import dev.era.accountability.repo.DeadlineRepository;
import dev.era.accountability.repo.ReminderTuningRepository;
import dev.era.accountability.repo.UserSettingsRepository;
import dev.era.accountability.service.ReminderSampler;
import dev.era.accountability.service.ReminderTuning;
import dev.era.accountability.service.StaticTemplates;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * The two test-mode commands: change the sampler's numbers, and see what a run
 * of them would look like.
 *
 * /simulate is never the real schedule. Timing is sampled, so every call returns
 * a different sequence; what will actually fire is whatever the tick has already
 * written into reminder_event. The command is for judging the shape, since the
 * alternative way to find out whether a setting is too annoying is to wait a
 * week per guess.
 */
@Component
public class TuningCommands {

    static final String HELP = """
            Test mode:
            /lambda            the sampler's numbers, and the rate right now
            <code>/lambda max 4</code>   change one (base min max spacing cap usercap ahead)
            /lambda reset      back to application.yml
            <code>/simulate 1</code>    one possible run of reminders, not the real one""";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM HH:mm");

    private static final List<String> KEYS =
            List.of("base", "min", "max", "spacing", "cap", "usercap", "ahead");

    private static final List<String> INTEGER_KEYS =
            List.of("spacing", "cap", "usercap", "ahead");

    private final DeadlineRepository deadlines;
    private final UserSettingsRepository userSettings;
    private final ReminderSampler sampler;
    private final ReminderTuning tuning;

    public TuningCommands(DeadlineRepository deadlines,
                          UserSettingsRepository userSettings,
                          ReminderSampler sampler,
                          ReminderTuning tuning) {
        this.deadlines = deadlines;
        this.userSettings = userSettings;
        this.sampler = sampler;
        this.tuning = tuning;
    }

    // -------------------------------------------------------------- /lambda

    public String lambda(long chatId, String rest) {
        if (rest.isBlank()) {
            return mono(report(chatId));
        }
        if (rest.equalsIgnoreCase("reset")) {
            tuning.reset();
            return mono("Overrides cleared, application.yml is authoritative again.\n\n" + report(chatId));
        }

        String[] parts = rest.split("\\s+");
        if (parts.length != 2) {
            return "Usage: <code>/lambda &lt;key&gt; &lt;value&gt;</code>\nkeys: " + String.join(" ", KEYS);
        }
        String key = parts[0].toLowerCase();
        if (!ReminderTuningRepository.COLUMNS.containsKey(key)) {
            return "Unknown key '%s'. Try: %s".formatted(key, String.join(" ", KEYS));
        }
        double value;
        try {
            value = Double.parseDouble(parts[1]);
        } catch (NumberFormatException e) {
            return "'%s' is not a number.".formatted(parts[1]);
        }
        if (value <= 0 && !key.equals("spacing")) {
            return "%s has to be greater than zero.".formatted(key);
        }

        Number stored = INTEGER_KEYS.contains(key) ? (Number) (int) value : (Number) value;
        try {
            tuning.set(key, stored);
        } catch (RuntimeException e) {
            // The CHECK constraints on reminder_tuning are the real guard, so a
            // rejected value arrives here as a database error, not as a bug.
            return "Postgres rejected that: " + rootMessage(e);
        }
        return mono("%s = %s\n\n%s".formatted(key, format(key, tuning.current(key)), report(chatId)));
    }

    private String report(long chatId) {
        var sb = new StringBuilder("Sampler tuning  (* = set from chat, else application.yml)\n\n");
        for (String key : KEYS) {
            boolean over = tuning.isOverridden(key);
            sb.append("%s %-8s %-8s".formatted(over ? "*" : " ", key, format(key, tuning.current(key))));
            if (over) {
                sb.append("was ").append(format(key, tuning.configured(key)));
            }
            sb.append('\n');
        }

        Instant now = Instant.now();
        var pending = deadlines.findPendingByChat(chatId);
        sb.append("\nRate right now:\n");
        if (pending.isEmpty()) {
            sb.append("  nothing pending.\n");
        }
        for (Deadline d : pending) {
            double rate = sampler.rateFor(now, d.dueAt());
            long meanGap = Math.round(24 * 60 / rate);
            sb.append("  #%d %s\n    lambda %.2f/day, mean gap %s, %s left\n".formatted(
                    d.id(), d.name(), rate, StaticTemplates.human(Duration.ofMinutes(meanGap)),
                    StaticTemplates.human(Duration.between(now, d.dueAt()))));
            // Worth calling out: while the ceiling binds, changing 'base' does
            // nothing at all, and you would conclude the command is broken
            // rather than that 'max' is the live knob.
            if (sampler.atCeiling(now, d.dueAt())) {
                sb.append("    pinned at max: raise 'max' here, not 'base'\n");
            }
        }

        sb.append("\nChanges apply to future draws. Reminders already scheduled keep\n")
          .append("their times: a drawn time is a decision, not a formula.");
        return sb.toString().stripTrailing();
    }

    // ------------------------------------------------------------ /simulate

    public String simulate(long chatId, String rest) {
        Optional<UserSettings> settings = userSettings.find(chatId);
        if (settings.isEmpty() || !settings.get().quietHoursSet()) {
            return "Set quiet hours first with /quiet HH:MM HH:MM, otherwise the "
                 + "sampler plans nothing and this would print an empty list.";
        }
        var d = resolve(chatId, rest.split("\\s+", 2)[0]);
        if (d.isEmpty()) {
            return "Usage: <code>/simulate &lt;id&gt;</code>   (see /list)";
        }

        ZoneId zone = settings.get().zone();
        Instant now = Instant.now();
        Instant dueAt = d.get().dueAt();
        var draws = sampler.simulate(settings.get(), now, dueAt, tuning.maxPerDeadlineDaily() * 8);

        var sb = new StringBuilder(
                "One possible run for #%d %s.\nThis is NOT the schedule: run it again and the times change.\n\n"
                        .formatted(d.get().id(), d.get().name()));
        Instant prev = now;
        for (Instant t : draws) {
            sb.append("  %s   +%s\n".formatted(
                    STAMP.format(t.atZone(zone)), StaticTemplates.human(Duration.between(prev, t))));
            prev = t;
        }
        if (draws.isEmpty()) {
            sb.append("  nothing: quiet hours or the deadline leave no room.\n");
        }
        sb.append("\n%d draw%s, stopping at %s.\n".formatted(
                draws.size(), draws.size() == 1 ? "" : "s", STAMP.format(dueAt.atZone(zone))));
        sb.append("Spacing and quiet hours are applied here; the daily caps are not.");
        return mono(sb.toString());
    }

    // -------------------------------------------------------------- helpers

    private Optional<Deadline> resolve(long chatId, String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return Optional.empty();
        }
        try {
            return deadlines.find(Long.parseLong(rawId.replace("#", "")), chatId);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Wraps a block in a monospace pre, which is the only way Telegram will
     * keep columns lined up: its default font is proportional, so a hand-padded
     * table reads as ragged noise. Escaping is required inside it too.
     */
    private static String mono(String body) {
        return "<pre>" + TelegramApi.escape(body) + "</pre>";
    }

    private static String format(String key, double v) {
        return INTEGER_KEYS.contains(key) ? String.valueOf((long) v) : "%.2f".formatted(v);
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String m = root.getMessage();
        return m == null ? root.getClass().getSimpleName() : m.lines().findFirst().orElse(m);
    }
}
