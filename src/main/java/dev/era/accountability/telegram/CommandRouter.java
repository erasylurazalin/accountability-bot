package dev.era.accountability.telegram;

import dev.era.accountability.domain.Commitment;
import dev.era.accountability.repo.AuditRepository;
import dev.era.accountability.repo.CheckInRepository;
import dev.era.accountability.repo.CommitmentRepository;
import dev.era.accountability.repo.StreakRepository;
import dev.era.accountability.repo.UserSettingsRepository;
import dev.era.accountability.service.CheckInService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.util.Optional;

@Component
public class CommandRouter {

    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);

    private static final String HELP = """
            Commands:
            /new <name>        create a daily commitment
            /list              show active commitments and today's status
            /done <id>         mark today complete
            /skip <id> <why>   skip today: a reason is required and is logged
            /streak            current and best streaks
            /stop <id>         deactivate a commitment
            /quiet HH:MM HH:MM hours when the bot stays silent
            /quiet             show current quiet hours
            /quiet off         clear them (this also stops all reminders)
            /help              this message""";

    private final CommitmentRepository commitments;
    private final CheckInRepository checkIns;
    private final StreakRepository streaks;
    private final UserSettingsRepository userSettings;
    private final CheckInService checkInService;
    private final AuditRepository audit;

    public CommandRouter(CommitmentRepository commitments,
                         CheckInRepository checkIns,
                         StreakRepository streaks,
                         UserSettingsRepository userSettings,
                         CheckInService checkInService,
                         AuditRepository audit) {
        this.commitments = commitments;
        this.checkIns = checkIns;
        this.streaks = streaks;
        this.userSettings = userSettings;
        this.checkInService = checkInService;
        this.audit = audit;
    }

    public String handle(long chatId, String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            return HELP;
        }

        // Telegram appends @botname to commands in groups.
        String[] parts = trimmed.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        int at = command.indexOf('@');
        if (at > 0) {
            command = command.substring(0, at);
        }
        String rest = parts.length > 1 ? parts[1].strip() : "";

        try {
            return switch (command) {
                case "/start", "/help" -> HELP;
                case "/new"    -> create(chatId, rest);
                case "/list"   -> list(chatId);
                case "/done"   -> done(chatId, rest);
                case "/skip"   -> skip(chatId, rest);
                case "/streak" -> streaks(chatId);
                case "/stop"   -> stop(chatId, rest);
                case "/quiet"  -> quiet(chatId, rest);
                default        -> "Unknown command.\n\n" + HELP;
            };
        } catch (RuntimeException e) {
            log.error("command '{}' failed", command, e);
            return "That didn't work. The error has been logged.";
        }
    }

    private String create(long chatId, String name) {
        if (name.isBlank()) {
            return "Usage: /new <name>";
        }
        if (name.length() > 120) {
            return "That name is too long (120 characters max).";
        }
        long id = commitments.create(chatId, name);
        userSettings.ensureExists(chatId);
        audit.record("owner", "CREATE", "commitment", id, null);

        var settings = userSettings.find(chatId);
        if (settings.isEmpty() || !settings.get().quietHoursSet()) {
            // Deliberately not defaulted. Reminders stay off until the hours are
            // set, because guessing a waking window is how a bot gets muted.
            return ("Created #%d: %s\nDue by midnight each day.\n\n"
                  + "Reminders are off until you set quiet hours, so I do not "
                  + "guess and wake you at 04:00. Set them with:\n"
                  + "/quiet 02:00 10:00   (silent between those times)")
                    .formatted(id, name);
        }
        return "Created #%d: %s\nDue by midnight each day.".formatted(id, name);
    }

    private String list(long chatId) {
        var active = commitments.findActiveByChat(chatId);
        if (active.isEmpty()) {
            return "Nothing active. Add one with /new <name>";
        }
        var sb = new StringBuilder("Active commitments:\n");
        for (Commitment c : active) {
            var open = checkIns.findOpen(c.id());
            String status = open.map(ci -> "pending").orElse("settled");
            int streak = streaks.find(c.id()).map(s -> s.currentLen()).orElse(0);
            sb.append("#%d %s (today: %s, streak %d)\n".formatted(c.id(), c.name(), status, streak));
        }
        return sb.toString().stripTrailing();
    }

    private String done(long chatId, String rest) {
        var c = resolve(chatId, rest.split("\\s+", 2)[0]);
        if (c.isEmpty()) {
            return "Usage: /done <id>   (see /list)";
        }
        return checkInService.markDone(c.get())
                .orElse("Nothing open for %s right now.".formatted(c.get().name()));
    }

    private String skip(long chatId, String rest) {
        String[] parts = rest.split("\\s+", 2);
        var c = resolve(chatId, parts[0]);
        if (c.isEmpty()) {
            return "Usage: /skip <id> <why>   (see /list)";
        }
        String excuse = parts.length > 1 ? parts[1] : "";
        return checkInService.markSkipped(c.get(), excuse)
                .orElse("Nothing open for %s right now.".formatted(c.get().name()));
    }

    private String streaks(long chatId) {
        var active = commitments.findActiveByChat(chatId);
        if (active.isEmpty()) {
            return "Nothing active yet.";
        }
        var sb = new StringBuilder("Streaks:\n");
        for (Commitment c : active) {
            var s = streaks.find(c.id());
            sb.append("#%d %s: current %d, best %d\n".formatted(
                    c.id(), c.name(),
                    s.map(v -> v.currentLen()).orElse(0),
                    s.map(v -> v.bestLen()).orElse(0)));
        }
        return sb.toString().stripTrailing();
    }

    private String stop(long chatId, String rest) {
        var c = resolve(chatId, rest.split("\\s+", 2)[0]);
        if (c.isEmpty()) {
            return "Usage: /stop <id>   (see /list)";
        }
        boolean stopped = commitments.deactivate(c.get().id(), chatId);
        if (stopped) {
            audit.record("owner", "DEACTIVATE", "commitment", c.get().id(), null);
        }
        return stopped ? "Deactivated #%d.".formatted(c.get().id()) : "Already inactive.";
    }

    /**
     * Quiet hours are stored unset until chosen, and the scheduler sends nothing
     * while they are unset. That is the fail-closed direction: the failure mode
     * is a silent bot, not a 04:00 notification.
     */
    private String quiet(long chatId, String rest) {
        userSettings.ensureExists(chatId);

        if (rest.isBlank()) {
            var s = userSettings.find(chatId);
            if (s.isEmpty() || !s.get().quietHoursSet()) {
                return "Quiet hours are not set, so no reminders will be sent.\n"
                     + "Set them with /quiet HH:MM HH:MM";
            }
            return "Quiet between %s and %s (%s). Reminders are on."
                    .formatted(s.get().quietHoursStart(), s.get().quietHoursEnd(), s.get().timezone());
        }

        if (rest.equalsIgnoreCase("off")) {
            userSettings.setQuietHours(chatId, null, null);
            audit.record("owner", "QUIET_OFF", "user_settings", chatId, null);
            return "Quiet hours cleared. That also switches reminders off, "
                 + "because the bot will not guess when you are asleep.";
        }

        String[] parts = rest.split("\\s+");
        if (parts.length != 2) {
            return "Usage: /quiet HH:MM HH:MM   (or /quiet off)";
        }
        LocalTime start;
        LocalTime end;
        try {
            start = LocalTime.parse(parts[0]);
            end = LocalTime.parse(parts[1]);
        } catch (DateTimeException e) {
            return "I could not read those times. Use 24-hour HH:MM, e.g. /quiet 02:00 10:00";
        }
        if (start.equals(end)) {
            return "Those are the same time. For no quiet hours at all, use /quiet off";
        }
        userSettings.setQuietHours(chatId, start, end);
        audit.record("owner", "QUIET_SET", "user_settings", chatId, null);
        return "Quiet between %s and %s. Reminders will arrive at unpredictable "
             + "times outside that, more often as the day runs out.".formatted(start, end);
    }

    private Optional<Commitment> resolve(long chatId, String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return Optional.empty();
        }
        try {
            return commitments.findByIdAndChat(Long.parseLong(rawId.replace("#", "")), chatId);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
