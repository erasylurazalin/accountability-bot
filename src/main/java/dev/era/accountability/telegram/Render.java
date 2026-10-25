package dev.era.accountability.telegram;

import dev.era.accountability.domain.Deadline;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every string the person sees, in one place.
 *
 * The formatting used to be spread across CommandRouter, CallbackRouter and
 * StaticTemplates, with two copies of the same date pattern and slightly
 * different card layouts. One renderer means a deadline looks the same whether
 * it arrived in a list, under a reminder, or after a tap.
 *
 * All of this is static and takes `now` as an argument: the UI is easy to
 * reason about when the only clock involved is passed in, and tests can put it
 * wherever they like.
 */
public final class Render {

    private Render() {}

    /** "27 Oct, 23:59" style. The week day is in the label, not the stamp. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM, HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    public static String menu(int pending, Deadline next, boolean quietSet,
                              ZoneId zone, Instant now) {
        var sb = new StringBuilder("<b>accountability</b>\n");
        if (pending == 0) {
            sb.append("Nothing pending.");
        } else {
            sb.append(pending == 1 ? "1 deadline" : pending + " deadlines");
            if (next != null) {
                sb.append(", next ").append(relative(next.dueAt(), now))
                  .append(" (").append(stamp(next.dueAt(), zone)).append(")");
            }
        }
        if (!quietSet) {
            // Said on every menu, because it is the difference between a bot
            // that works and a bot that silently sends nothing.
            sb.append("\n\n<b>Reminders are off.</b> Set quiet hours and they start.");
        }
        return sb.toString();
    }

    /**
     * The card for one deadline. Same shape everywhere: name, when, how long is
     * left. Actions live in the keyboard, never in the prose.
     */
    public static String card(Deadline d, ZoneId zone, Instant now) {
        return "<b>%s</b>\n%s  <i>%s</i>".formatted(
                escape(d.name()), stamp(d.dueAt(), zone), relative(d.dueAt(), now));
    }


    /**
     * Pending grouped by when, because "today" and "in nine days" are not the
     * same thing even when the timestamps sort the same way. Returns the text
     * only; the tappable rows are built by Keyboards from the same list.
     */
    public static String list(List<Deadline> pending, List<Deadline> lapsed,
                              ZoneId zone, Instant now) {
        if (pending.isEmpty() && lapsed.isEmpty()) {
            return "<b>Pending</b>\n\nNothing pending.\nAdd one below.";
        }

        var sb = new StringBuilder("<b>Pending</b>");
        if (pending.isEmpty()) {
            sb.append("\n\nNothing pending.");
        } else {
            for (var group : group(pending, zone, now).entrySet()) {
                sb.append("\n\n<b>").append(group.getKey()).append("</b>\n");
                boolean first = true;
                for (Deadline d : group.getValue()) {
                    if (!first) {
                        sb.append('\n');
                    }
                    first = false;
                    sb.append("#").append(d.id()).append("  ")
                      .append(escape(d.name())).append("  <i>")
                      .append(relative(d.dueAt(), now)).append("</i>");
                }
            }
        }

        if (!lapsed.isEmpty()) {
            sb.append("\n\n<b>Went past</b>\n");
            boolean first = true;
            for (Deadline d : lapsed) {
                if (!first) {
                    sb.append('\n');
                }
                first = false;
                sb.append("#").append(d.id()).append("  ").append(escape(d.name()))
                  .append("  <i>").append(stamp(d.dueAt(), zone)).append("</i>");
            }
            sb.append("\n\nOpen one below to put it back.");
        }
        return sb.toString();
    }

    /** "In 1d 6h", "2h ago", used on cards and in the list. */
    public static String relative(Instant dueAt, Instant now) {
        Duration d = Duration.between(now, dueAt);
        if (d.isNegative()) {
            return human(d.negated()) + " ago";
        }
        return "in " + human(d);
    }

    /**
     * The same compaction the reminder line uses, so a card and a reminder never
     * disagree about what "2d 4h" means.
     */
    public static String human(Duration d) {
        long minutes = Math.max(0, d.toMinutes());
        long days = minutes / (60 * 24);
        long hours = (minutes % (60 * 24)) / 60;
        long mins = minutes % 60;
        if (days > 0) {
            return "%dd %dh".formatted(days, hours);
        }
        if (hours > 0) {
            return "%dh%02d".formatted(hours, mins);
        }
        return "%dm".formatted(mins);
    }

    /** "Tue 27 Oct, 23:59". */
    public static String stamp(Instant at, ZoneId zone) {
        return STAMP.format(at.atZone(zone));
    }

    /** "Tue 27 Oct", for a date without a time. */
    public static String day(LocalDate date) {
        return DAY.format(date);
    }

    public static String clock(LocalTime time) {
        return TIME.format(time);
    }

    // ------------------------------------------------------------------
    // Asking
    // ------------------------------------------------------------------

    public static String askName() {
        return "<b>New deadline</b>\n\nWhat is it called? Send the name as a message.";
    }

    public static String askRename(String current) {
        return "<b>%s</b>\n\nWhat should it be called instead?".formatted(escape(current));
    }

    public static String askDate(String name) {
        return "<b>%s</b>\n\nWhen is it due?".formatted(escape(name));
    }

    public static String askDateTyped(String name) {
        return "<b>%s</b>\n\nSend the date as <code>YYYY-MM-DD</code>, "
             + "or <code>today</code> or <code>tomorrow</code>.".formatted(escape(name));
    }

    public static String askTime(String name, LocalDate date) {
        return "<b>%s</b>\n%s. What time?".formatted(escape(name), day(date));
    }

    public static String askTimeTyped(String name, LocalDate date) {
        return "<b>%s</b>\n%s. Send the time as <code>HH:MM</code>."
                .formatted(escape(name), day(date));
    }

    public static String askCustomQuiet() {
        return "<b>Quiet hours</b>\n\nSend two times, from and to, as "
             + "<code>23:00 07:00</code>.";
    }

    /** The last look before a deadline exists, so a mistapped date is cheap to fix. */
    public static String draftCard(String name, Instant dueAt, ZoneId zone, Instant now) {
        return "<b>%s</b>\n%s  <i>%s</i>\n\nAdd it?".formatted(
                escape(name), stamp(dueAt, zone), relative(dueAt, now));
    }

    /** Why nothing will be sent yet, shown right after the deadline is created. */
    public static String quietWarning() {
        return "\n\n<b>Reminders are off.</b> Set quiet hours and they start.";
    }

    public static String gone() {
        return "That one is gone.";
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    public static String quiet(java.time.LocalTime start, java.time.LocalTime end) {
        if (start == null || end == null) {
            return "<b>Quiet hours</b>\n\nNot set, so the bot sends nothing at all. "
                 + "Pick a window and reminders start.";
        }
        return "<b>Quiet hours</b>\n\nSilent %s to %s. Reminders arrive outside that."
                .formatted(clock(start), clock(end));
    }

    public static String help() {
        return """
                <b>accountability</b>
                Deadlines come back as reminders at random times, more often as the
                deadline gets close.

                <b>Buttons</b>
                Use the menu below, or the Menu button next to the text box.
                Nothing here needs a command typed out.

                <b>Still typed</b>
                /start  the menu
                /list   what is pending
                /help   this""";
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Today, Tomorrow, Later, and overdue first, since that is the order of worry. */
    public static Map<String, List<Deadline>> group(List<Deadline> pending, ZoneId zone, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        var groups = new LinkedHashMap<String, List<Deadline>>();

        for (Deadline d : pending) {
            LocalDate due = LocalDate.ofInstant(d.dueAt(), zone);
            String label;
            if (d.dueAt().isBefore(now)) {
                label = "Overdue";
            } else if (due.equals(today)) {
                label = "Today";
            } else if (due.equals(today.plusDays(1))) {
                label = "Tomorrow";
            } else {
                label = Render.day(due);
            }
            groups.computeIfAbsent(label, k -> new ArrayList<>()).add(d);
        }
        return groups;
    }

    /** User text into HTML mode. Telegram rejects the whole send over one stray angle bracket. */
    public static String escape(String raw) {
        return TelegramApi.escape(raw);
    }
}
