package dev.era.accountability.service;

import dev.era.accountability.domain.Commitment;

import java.util.List;

/**
 * The swap point demanded by NOTES.md section 5.3: reminder copy may come from a
 * hosted OpenAI-compatible API or from a local model, and switching between them
 * must be a configuration change rather than a rewrite.
 *
 * Implementations are called by the nightly batch only — never on the send path.
 * A generator that throws or returns nothing is not an error: the consumer falls
 * back to static templates and the reminder still fires (NOTES.md section 5.1).
 */
public interface ReminderTextGenerator {

    /** @return up to {@code count} candidate lines; may be empty. */
    List<String> generate(Commitment commitment, int urgencyTier, int count);

    /** Human-readable provenance, stored in reminder_text.generated_by. */
    String name();
}
