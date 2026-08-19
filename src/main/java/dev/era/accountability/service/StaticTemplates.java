package dev.era.accountability.service;

import dev.era.accountability.domain.Commitment;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The fail-safe floor. Whatever else breaks: the generator, the network, the
 * machine that runs the model: a reminder still goes out.
 */
public final class StaticTemplates {

    private StaticTemplates() {}

    private static final List<List<String>> BY_TIER = List.of(
            List.of("%s is still open today.",
                    "Reminder: %s.",
                    "%s hasn't been checked off yet."),
            List.of("%s is still sitting there. /done or /skip.",
                    "Second ask: %s.",
                    "%s. The window is closing."),
            List.of("%s: last call before this one expires.",
                    "%s is about to be logged as a miss.",
                    "Final reminder: %s. Skipping needs an excuse in writing."));

    public static String pick(Commitment commitment, int urgencyTier) {
        var tier = BY_TIER.get(Math.clamp(urgencyTier, 0, BY_TIER.size() - 1));
        var template = tier.get(ThreadLocalRandom.current().nextInt(tier.size()));
        return template.formatted(commitment.name());
    }
}
