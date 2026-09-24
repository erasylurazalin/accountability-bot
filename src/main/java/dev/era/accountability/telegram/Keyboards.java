package dev.era.accountability.telegram;

import dev.era.accountability.domain.Deadline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Inline keyboards, as the nested maps the Bot API expects.
 *
 * The whole of a button's meaning goes into its callback_data, so tapping one
 * carries its own context and the bot never has to remember where you were.
 * That keeps this stateless, which matters more than it sounds: a wizard that
 * remembers your position gets confused by two chats, a restart, or a tap on a
 * message from yesterday.
 *
 * callback_data is capped at 64 bytes by Telegram, so the encoding is terse:
 * a verb, a colon, an id.
 */
public final class Keyboards {

    private Keyboards() {}

    public static final String DONE    = "done";
    public static final String SNOOZE  = "snz";
    public static final String REMOVE  = "rm";
    public static final String CONFIRM = "rmy";
    public static final String CANCEL  = "no";
    public static final String LIST    = "ls";

    private static Map<String, String> button(String text, String data) {
        return Map.of("text", text, "callback_data", data);
    }

    /** The row under a deadline, in /list and under every reminder. */
    public static Object forDeadline(long id) {
        return Map.of("inline_keyboard", List.of(List.of(
                button("Done", DONE + ":" + id),
                button("+1 day", SNOOZE + ":" + id),
                button("Remove", REMOVE + ":" + id))));
    }

    /**
     * Deleting is the one irreversible tap, so it asks first. Done and snooze
     * do not, because both are undoable with /edit.
     */
    public static Object confirmRemove(long id) {
        return Map.of("inline_keyboard", List.of(List.of(
                button("Yes, delete", CONFIRM + ":" + id),
                button("Keep it", CANCEL + ":" + id))));
    }

    /** One row per deadline, so a list is actionable without typing an id. */
    public static Object forList(List<Deadline> deadlines) {
        var rows = new ArrayList<Object>();
        for (Deadline d : deadlines) {
            rows.add(List.of(
                    button("#" + d.id() + " done", DONE + ":" + d.id()),
                    button("+1 day", SNOOZE + ":" + d.id()),
                    button("remove", REMOVE + ":" + d.id())));
        }
        return rows.isEmpty() ? null : Map.of("inline_keyboard", rows);
    }
}
