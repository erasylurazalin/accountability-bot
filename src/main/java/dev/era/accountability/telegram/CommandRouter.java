package dev.era.accountability.telegram;

import dev.era.accountability.config.BotProperties;
import dev.era.accountability.domain.Deadline;
import dev.era.accountability.repo.AuditRepository;
import dev.era.accountability.repo.DeadlineRepository;
import dev.era.accountability.repo.ReminderRepository;
import dev.era.accountability.repo.UserSettingsRepository;
import dev.era.accountability.service.StaticTemplates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class CommandRouter {

    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM HH:mm");

    private static final String HELP = """
            <b>Deadlines</b>
            <code>/new tomorrow 23:59 hand in essay</code>
            <code>/new 2026-09-01 18:00 renew passport</code>
            /list  what is pending
            <code>/edit 1 2026-09-02 09:00</code>  move it
            <code>/edit 1 a better name</code>  rename it
            <code>/remove 1</code>  delete it

            <b>Settings</b>
            <code>/quiet 02:00 10:00</code>  hours to stay silent
            /quiet  show them
            <code>/quiet off</code>  clear them, which stops all reminders

            Buttons under each deadline do the same without typing an id.""";

    private final DeadlineRepository deadlines;
    private final ReminderRepository reminders;
    private final UserSettingsRepository userSettings;
    private final AuditRepository audit;
    private final TuningCommands tuningCommands;
    private final BotProperties botProps;

    public CommandRouter(DeadlineRepository deadlines,
                         ReminderRepository reminders,
                         UserSettingsRepository userSettings,
                         AuditRepository audit,
                         TuningCommands tuningCommands,
                         BotProperties botProps) {
        this.deadlines = deadlines;
        this.reminders = reminders;
        this.userSettings = userSettings;
        this.audit = audit;
        this.tuningCommands = tuningCommands;
        this.botProps = botProps;
    }

    public Reply handle(long chatId, String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            return Reply.of(help());
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
                case "/start", "/help" -> Reply.of(help());
                case "/new"    -> create(chatId, rest);
                case "/list"   -> list(chatId);
                case "/edit"   -> edit(chatId, rest);
                case "/remove" -> remove(chatId, rest);
                case "/quiet"  -> Reply.of(quiet(chatId, rest));

                // Test mode. Not a security boundary: TelegramPoller has already
                // dropped every chat but the owner's before anything reaches
                // here. It keeps /help honest and stops a half-remembered
                // /lambda a year from now silently changing the sampler.
                case "/lambda"   -> Reply.of(testOnly(() -> tuningCommands.lambda(chatId, rest)));
                case "/simulate" -> Reply.of(testOnly(() -> tuningCommands.simulate(chatId, rest)));

                default -> Reply.of("Unknown command.\n\n" + help());
            };
        } catch (RuntimeException e) {
            log.error("command '{}' failed", command, e);
            return Reply.of("That didn't work. The error has been logged.");
        }
    }

    /** What fills the blue Menu button. Registered once at startup. */
    public List<Map<String, String>> menuCommands() {
        var out = new ArrayList<Map<String, String>>(List.of(
                Map.of("command", "new",    "description", "Add a deadline"),
                Map.of("command", "list",   "description", "What is pending"),
                Map.of("command", "edit",   "description", "Move or rename one"),
                Map.of("command", "remove", "description", "Delete one"),
                Map.of("command", "quiet",  "description", "Hours the bot stays silent"),
                Map.of("command", "help",   "description", "How this works")));
        if (botProps.testMode()) {
            out.add(Map.of("command", "lambda",   "description", "Sampler tuning"));
            out.add(Map.of("command", "simulate", "description", "A possible run of reminders"));
        }
        return out;
    }

    private String help() {
        return botProps.testMode() ? HELP + "\n\n" + TuningCommands.HELP : HELP;
    }

    private String testOnly(java.util.function.Supplier<String> body) {
        if (!botProps.testMode()) {
            return "That command is only available in test mode.\n"
                 + "Set BOT_TESTMODE=true and restart to switch it on.";
        }
        return body.get();
    }

    private Reply create(long chatId, String rest) {
        String[] parts = rest.split("\\s+", 3);
        if (parts.length < 3) {
            return Reply.of("""
                    <b>Usage</b>
                    <code>/new tomorrow 23:59 hand in essay</code>
                    <code>/new 2026-09-01 18:00 renew passport</code>""");
        }

        userSettings.ensureExists(chatId);
        var settings = userSettings.find(chatId).orElseThrow();
        var zone = settings.zone();

        Instant dueAt;
        try {
            dueAt = parseWhen(parts[0], parts[1], zone);
        } catch (DateTimeException e) {
            return Reply.of("I could not read that date or time. Use <code>YYYY-MM-DD HH:MM</code>, "
                          + "or <code>today</code>/<code>tomorrow</code> followed by <code>HH:MM</code>.");
        }

        String name = parts[2].strip();
        if (name.length() > 120) {
            return Reply.of("That name is too long (120 characters max).");
        }
        if (!dueAt.isAfter(Instant.now())) {
            return Reply.of("That is already in the past. Nothing to remind you about.");
        }

        long id = deadlines.create(chatId, name, dueAt);
        audit.record("owner", "CREATE", "deadline", id, null);

        String head = describe(chatId, id);
        if (!settings.quietHoursSet()) {
            // Fail-closed: reminders stay off rather than the bot guessing when
            // you sleep and pinging at 04:00.
            return Reply.of(head + "\n\nReminders are off until you set quiet hours:\n"
                                 + "<code>/quiet HH:MM HH:MM</code>",
                            Keyboards.forDeadline(id));
        }
        return Reply.of(head, Keyboards.forDeadline(id));
    }

    /** One deadline as a block of HTML. Shared by /new, /edit and button taps. */
    public String describe(long chatId, long id) {
        var d = deadlines.find(id, chatId);
        if (d.isEmpty()) {
            return "That one is gone.";
        }
        var zone = zoneFor(chatId);
        return "<b>%s</b>\n%s  <i>%s left</i>".formatted(
                TelegramApi.escape(d.get().name()),
                STAMP.format(d.get().dueAt().atZone(zone)),
                StaticTemplates.human(Duration.between(Instant.now(), d.get().dueAt())));
    }

    private Reply list(long chatId) {
        var pending = deadlines.findPendingByChat(chatId);
        var zone = zoneFor(chatId);
        Instant now = Instant.now();

        var sb = new StringBuilder();
        if (pending.isEmpty()) {
            sb.append("Nothing pending.\n\nAdd one with <code>/new tomorrow 23:59 name</code>");
        } else {
            sb.append("<b>Pending</b>\n");
            for (Deadline d : pending) {
                sb.append("\n#%d  <b>%s</b>\n     %s  <i>%s left</i>\n".formatted(
                        d.id(), TelegramApi.escape(d.name()),
                        STAMP.format(d.dueAt().atZone(zone)),
                        StaticTemplates.human(Duration.between(now, d.dueAt()))));
            }
        }

        // Lapsed ones are shown briefly so a deadline does not simply vanish
        // the moment it goes past.
        var lapsed = deadlines.findRecentlyExpired(chatId, now.minus(Duration.ofDays(7)));
        if (!lapsed.isEmpty()) {
            sb.append("\n<b>Went past</b>\n");
            for (Deadline d : lapsed) {
                sb.append("#%d  %s  <i>%s</i>\n".formatted(
                        d.id(), TelegramApi.escape(d.name()), STAMP.format(d.dueAt().atZone(zone))));
            }
            sb.append("\n<code>/edit &lt;id&gt; tomorrow 18:00</code> puts one back.");
        }
        return Reply.of(sb.toString().stripTrailing(), Keyboards.forList(pending));
    }

    /**
     * Two forms in one command: a new time, or a new name. Which one you meant
     * is decided by whether the next word parses as a date, so that renaming
     * something to "tomorrow drinks" is the only case that could surprise you.
     */
    private Reply edit(long chatId, String rest) {
        String[] parts = rest.split("\\s+", 2);
        var d = resolve(chatId, parts[0]);
        if (d.isEmpty() || parts.length < 2) {
            return Reply.of("""
                    <b>Usage</b>
                    <code>/edit 1 2026-09-01 18:00</code>  move it
                    <code>/edit 1 a better name</code>  rename it""");
        }
        var zone = zoneFor(chatId);

        String[] tail = parts[1].split("\\s+", 2);
        if (tail.length >= 2) {
            try {
                Instant dueAt = parseWhen(tail[0], tail[1].split("\\s+")[0], zone);
                if (!dueAt.isAfter(Instant.now())) {
                    return Reply.of("That is already in the past.");
                }
                deadlines.reschedule(d.get().id(), chatId, dueAt);
                audit.record("owner", "RESCHEDULE", "deadline", d.get().id(), null);
                // Queued reminders were drawn against the old time, so they are
                // the wrong shape now. Drop them and let the tick redraw.
                reminders.cancelUnsentFor(d.get().id());
                return Reply.of(describe(chatId, d.get().id()), Keyboards.forDeadline(d.get().id()));
            } catch (DateTimeException ignored) {
                // Not a date, so it is a rename. Fall through.
            }
        }

        String name = parts[1].strip();
        if (name.length() > 120) {
            return Reply.of("That name is too long (120 characters max).");
        }
        deadlines.rename(d.get().id(), chatId, name);
        audit.record("owner", "RENAME", "deadline", d.get().id(), null);
        return Reply.of(describe(chatId, d.get().id()), Keyboards.forDeadline(d.get().id()));
    }

    private Reply remove(long chatId, String rest) {
        var d = resolve(chatId, rest.split("\\s+", 2)[0]);
        if (d.isEmpty()) {
            return Reply.of("Usage: <code>/remove &lt;id&gt;</code>   (see /list)");
        }
        // Typed removal confirms too, so the id you fat-fingered is shown back
        // to you by name before anything is deleted.
        return Reply.of("Delete <b>%s</b>?".formatted(TelegramApi.escape(d.get().name())),
                        Keyboards.confirmRemove(d.get().id()));
    }

    private ZoneId zoneFor(long chatId) {
        return userSettings.find(chatId).map(s -> s.zone()).orElse(ZoneId.of("Asia/Almaty"));
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
        return ("Quiet between %s and %s. Reminders arrive at unpredictable times "
              + "outside that.").formatted(start, end);
    }

    /**
     * atZone resolves a daylight-saving gap forward rather than throwing, so a
     * time inside a deleted hour lands at the first instant that exists.
     */
    private static Instant parseWhen(String date, String time, ZoneId zone) {
        LocalDate day = switch (date.toLowerCase()) {
            case "today"    -> LocalDate.now(zone);
            case "tomorrow" -> LocalDate.now(zone).plusDays(1);
            default         -> LocalDate.parse(date);
        };
        return day.atTime(LocalTime.parse(time)).atZone(zone).toInstant();
    }

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
}
