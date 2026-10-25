package dev.era.accountability.service;

import dev.era.accountability.domain.Deadline;

import java.time.Instant;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reminder wording. Three tiers, picked from how far through its life the
 * deadline is.
 *
 * There is deliberately no model anywhere near this. A reminder that depends on
 * a third party being up at the moment it matters is a reminder that does not
 * arrive, and the failure is silent.
 */
public final class StaticTemplates {

    private StaticTemplates() {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM HH:mm");

    private static final List<List<String>> BY_TIER = List.of(
            List.of("%s is on the list.",
                    "Reminder: %s.",
                    "%s, still open."),
            List.of("%s is still sitting there.",
                    "Halfway gone: %s.",
                    "%s. Time is going."),
            List.of("%s: this one is close.",
                    "%s is nearly out of time.",
                    "Last stretch: %s."));

    /**
     * Messages go out with parse_mode HTML, so the name has to be escaped: a
     * deadline called "a < b" would otherwise be a malformed tag and Telegram
     * would reject the whole send.
     */
    public static String pick(Deadline d, Instant now) {
        var tier = BY_TIER.get(Math.clamp(d.urgencyTier(now), 0, BY_TIER.size() - 1));
        String name = "<b>" + dev.era.accountability.telegram.TelegramApi.escape(d.name()) + "</b>";
        return tier.get(ThreadLocalRandom.current().nextInt(tier.size())).formatted(name);
    }

}
