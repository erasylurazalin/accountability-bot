package dev.era.accountability.telegram;

import dev.era.accountability.domain.Deadline;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Inline keyboards, as the nested maps the Bot API expects.
 *
 * The meaning of a button goes into its callback_data, so a tap carries its own
 * context and the bot never has to remember where you were. That is what keeps
 * a tap on last week's message working after a restart. The one exception is
 * typed input: see UiDraftRepository for why that needs a row.
 *
 * callback_data is capped at 64 bytes, so the encoding is terse: a verb, a
 * colon, an argument. The longest here is a date, "n:d:2026-10-27", at 14.
 */
public final class Keyboards {

    private Keyboards() {}

    // Verbs. The first five predate the menu and are kept as they were, because
    // old reminder buttons are still sitting in the chat history.
    public static final String DONE        = "done";
    public static final String SNOOZE      = "snz";
    public static final String REMOVE      = "rm";
    public static final String CONFIRM     = "rmy";
    public static final String CANCEL      = "no";

    public static final String MENU        = "m";
    public static final String LIST        = "ls";
    public static final String HELP        = "h";
    public static final String VIEW        = "v";
    public static final String NEW         = "n";
    public static final String EDIT        = "ed";
    public static final String QUIET       = "q";
    public static final String SNOOZE_HOUR = "snzh";

    /** An empty inline keyboard, which is how a message gets its buttons removed. */
    public static final Object NONE = Map.of("inline_keyboard", List.of());

    private static Map<String, String> button(String text, String data) {
        return Map.of("text", text, "callback_data", data);
    }

    private static Object rows(List<List<Map<String, String>>> rows) {
        return Map.of("inline_keyboard", rows);
    }

    private static List<Map<String, String>> row(String text, String data) {
        return List.of(button(text, data));
    }

    // ------------------------------------------------------------------
    // The way in
    // ------------------------------------------------------------------

    public static Object menu() {
        return rows(List.of(
                List.of(button("New deadline", NEW),
                        button("Pending", LIST)),
                List.of(button("Quiet hours", QUIET),
                        button("How it works", HELP))));
    }

    public static Object toMenu() {
        return rows(List.of(row("Menu", MENU)));
    }

    /** The only way out of a question, so a prompt is never a dead end. */
    public static Object cancelOnly(String verb) {
        return rows(List.of(row("Cancel", verb + ":x:")));
    }

    // ------------------------------------------------------------------
    // One deadline
    // ------------------------------------------------------------------

    /**
     * The actions under a deadline, on its card and under every reminder. Done
     * and snooze are safe to tap twice; deleting asks first.
     */
    public static Object card(long id) {
        return rows(List.of(
                List.of(button("Done", DONE + ":" + id),
                        button("+1h", SNOOZE_HOUR + ":" + id),
                        button("+1 day", SNOOZE + ":" + id)),
                List.of(button("Edit", EDIT + ":" + id),
                        button("Remove", REMOVE + ":" + id))));
    }

    public static Object confirmRemove(long id) {
        return rows(List.of(List.of(
                button("Yes, delete", CONFIRM + ":" + id),
                button("Keep it", CANCEL + ":" + id))));
    }

    /** Which half of editing: moving is a picker, renaming is typed. */
    public static Object editMenu(long id) {
        return rows(List.of(
                List.of(button("Move it", EDIT + ":m:" + id),
                        button("Rename it", EDIT + ":r:" + id)),
                row("Back", VIEW + ":" + id)));
    }

    // ------------------------------------------------------------------
    // New deadline and moving one
    // ------------------------------------------------------------------

    /**
     * Seven days as buttons: today and tomorrow by name, then the weekdays.
     * Anyone planning further out types a date instead.
     */
    public static Object dates(ZoneId zone, Instant now, String verb) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        var top = new ArrayList<List<Map<String, String>>>();
        top.add(List.of(
                button("Today", verb + ":d:" + today),
                button("Tomorrow", verb + ":d:" + today.plusDays(1))));
        var week = new ArrayList<Map<String, String>>();
        for (int i = 2; i <= 6; i++) {
            LocalDate day = today.plusDays(i);
            week.add(button(Render.day(day), verb + ":d:" + day));
        }
        top.add(week.subList(0, 3));
        top.add(week.subList(3, 5));
        top.add(List.of(
                button("Another date", verb + ":dx:"),
                button("Cancel", verb + ":x:")));
        return rows(top);
    }

    /**
     * The times that come up most, then a way out for everything else. Three per
     * row is what fits a phone without wrapping.
     */
    public static Object times(String verb) {
        var top = new ArrayList<List<Map<String, String>>>();
        top.add(List.of(
                button("09:00", verb + ":t:0900"),
                button("12:00", verb + ":t:1200"),
                button("15:00", verb + ":t:1500")));
        top.add(List.of(
                button("18:00", verb + ":t:1800"),
                button("21:00", verb + ":t:2100"),
                button("23:59", verb + ":t:2359")));
        top.add(List.of(
                button("Another time", verb + ":tx:"),
                button("Back", verb + ":b:")));
        return rows(top);
    }

    public static Object confirmDraft(String verb) {
        return rows(List.of(
                row("Add it", verb + ":ok"),
                List.of(button("Back", verb + ":b:"),
                        button("Cancel", verb + ":x:"))));
    }

    // ------------------------------------------------------------------
    // Quiet hours
    // ------------------------------------------------------------------

    /** Three windows that suit a student, a way to type one, and the off switch. */
    public static Object quiet(boolean currentlySet) {
        var top = new ArrayList<List<Map<String, String>>>();
        top.add(List.of(
                button("22:00 to 08:00", QUIET + ":p:2200-0800"),
                button("23:00 to 07:00", QUIET + ":p:2300-0700")));
        top.add(List.of(
                button("00:00 to 09:00", QUIET + ":p:0000-0900"),
                button("Another window", QUIET + ":c")));
        if (currentlySet) {
            top.add(row("Turn reminders off", QUIET + ":off"));
        }
        top.add(row("Menu", MENU));
        return rows(top);
    }

    // ------------------------------------------------------------------
    // Lists
    // ------------------------------------------------------------------

    /**
     * One full-width button per deadline. The old list put three buttons under
     * every row, so six deadlines meant eighteen buttons: unreadable, and every
     * tap had to be aimed.
     */
    public static Object listRows(List<Deadline> pending, List<Deadline> lapsed,
                                  ZoneId zone, Instant now) {
        var top = new ArrayList<List<Map<String, String>>>();
        for (Deadline d : pending) {
            top.add(row(
                    "#" + d.id() + " " + truncate(d.name()) + ", " + Render.relative(d.dueAt(), now),
                    VIEW + ":" + d.id()));
        }
        for (Deadline d : lapsed) {
            top.add(row("#" + d.id() + " " + truncate(d.name()) + ", went past",
                    VIEW + ":" + d.id()));
        }
        top.add(List.of(button("New deadline", NEW), button("Menu", MENU)));
        return rows(top);
    }

    /** Button labels are not HTML, but Telegram still cannot lay out an essay. */
    private static String truncate(String name) {
        String flat = name.replaceAll("\\s+", " ").strip();
        return flat.length() <= 34 ? flat : flat.substring(0, 33) + "\u2026";
    }
}
