package dev.era.accountability.telegram;

import dev.era.accountability.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The typed interface, now deliberately small.
 *
 * Creating a deadline used to look like `/new tomorrow 23:59 hand in essay`:
 * three things to remember, one line, and a usage block if any of them was
 * wrong. The buttons do that job now, so what is left here is the handful of
 * commands worth having, plus a way to find the buttons again.
 *
 * Retired commands are not errors. A half-remembered /quiet gets the menu with a
 * line saying where it went, which is more useful than a usage dump.
 */
@Component
public class CommandRouter {

    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);

    private static final List<String> RETIRED =
            List.of("/new", "/edit", "/remove", "/quiet", "/settings", "/menu");

    private final Screens screens;
    private final TuningCommands tuningCommands;
    private final BotProperties botProps;

    public CommandRouter(Screens screens, TuningCommands tuningCommands, BotProperties botProps) {
        this.screens = screens;
        this.tuningCommands = tuningCommands;
        this.botProps = botProps;
    }

    public UiAction handle(long chatId, String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            return screens.menu(chatId);
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
                case "/start" -> screens.menu(chatId);
                case "/help"  -> screens.help();
                case "/list"           -> screens.list(chatId);
                // Test mode. Not a security boundary: TelegramPoller has already
                // dropped every chat but the owner's before anything reaches
                // here. It keeps /help honest and stops a half-remembered
                // /lambda a year from now silently changing the sampler.
                case "/lambda"   -> UiAction.send(testOnly(() -> tuningCommands.lambda(chatId, rest)));
                case "/simulate" -> UiAction.send(testOnly(() -> tuningCommands.simulate(chatId, rest)));
                default -> RETIRED.contains(command)
                        ? retired(chatId)
                        : screens.menu(chatId);
            };
        } catch (RuntimeException e) {
            log.error("command '{}' failed", command, e);
            return UiAction.send("That did not work. The error has been logged.");
        }
    }

    private UiAction retired(long chatId) {
        var menu = screens.menu(chatId);
        return new UiAction(UiAction.Mode.SEND,
                "That is a button now.\n\n" + menu.text(), menu.markup(), null, false, null);
    }

    /** What fills the blue Menu button. Registered once at startup. */
    public List<Map<String, String>> menuCommands() {
        var out = new ArrayList<Map<String, String>>(List.of(
                Map.of("command", "start", "description", "The menu"),
                Map.of("command", "list",  "description", "What is pending"),
                Map.of("command", "help",  "description", "How this works")));
        if (botProps.testMode()) {
            out.add(Map.of("command", "lambda",   "description", "Sampler tuning"));
            out.add(Map.of("command", "simulate", "description", "A possible run of reminders"));
        }
        return out;
    }

    private String testOnly(java.util.function.Supplier<String> body) {
        if (!botProps.testMode()) {
            return "That command is only available in test mode.\n"
                 + "Set BOT_TESTMODE=true and restart to switch it on.";
        }
        return body.get();
    }
}
