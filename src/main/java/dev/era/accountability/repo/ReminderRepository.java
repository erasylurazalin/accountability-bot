package dev.era.accountability.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ReminderRepository {

    public record Claim(long id, long commitmentId, long chatId, String body) {}

    private final JdbcClient jdbc;

    public ReminderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the right to send one reminder. The unique constraint on
     * idempotency_key is what prevents a restart from double-firing: a second
     * attempt for the same (commitment, window, slot) loses the insert race and
     * gets an empty result rather than a duplicate message.
     */
    public Optional<Long> claim(long commitmentId, Long checkInId, String idempotencyKey,
                                Instant scheduledFor, String body) {
        try {
            Long id = jdbc.sql("""
                            INSERT INTO reminder_event
                                (commitment_id, check_in_id, idempotency_key, scheduled_for, body)
                            VALUES (:commitmentId, :checkInId, :key, :scheduledFor, :body)
                            ON CONFLICT (idempotency_key) DO NOTHING
                            RETURNING id
                            """)
                    .param("commitmentId", commitmentId)
                    .param("checkInId", checkInId)
                    .param("key", idempotencyKey)
                    .param("scheduledFor", java.sql.Timestamp.from(scheduledFor))
                    .param("body", body)
                    .query(Long.class)
                    .optional()
                    .orElse(null);
            return Optional.ofNullable(id);
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    public void markSent(long reminderEventId, Long telegramMessageId) {
        jdbc.sql("UPDATE reminder_event SET sent_at = now(), telegram_message_id = :mid WHERE id = :id")
                .param("id", reminderEventId)
                .param("mid", telegramMessageId)
                .update();
    }

    /** Abandons a claim whose send failed, so the slot can be retried. */
    public void releaseClaim(long reminderEventId) {
        jdbc.sql("DELETE FROM reminder_event WHERE id = :id AND sent_at IS NULL")
                .param("id", reminderEventId)
                .update();
    }

    public int countSentInWindow(long commitmentId, Instant from, Instant to) {
        Integer n = jdbc.sql("""
                        SELECT count(*) FROM reminder_event
                        WHERE commitment_id = :id AND scheduled_for >= :from AND scheduled_for < :to
                        """)
                .param("id", commitmentId)
                .param("from", java.sql.Timestamp.from(from))
                .param("to", java.sql.Timestamp.from(to))
                .query(Integer.class)
                .single();
        return n == null ? 0 : n;
    }

    public Optional<Instant> lastScheduledFor(long commitmentId) {
        return jdbc.sql("SELECT max(scheduled_for) FROM reminder_event WHERE commitment_id = :id")
                .param("id", commitmentId)
                .query((rs, n) -> {
                    var ts = rs.getTimestamp(1);
                    return ts == null ? null : ts.toInstant();
                })
                .optional()
                .filter(java.util.Objects::nonNull);
    }

    /**
     * Claims that were never sent — the process died between the INSERT and the
     * Telegram call. Recovered only inside a grace window; an ancient orphan is
     * a reminder nobody wants any more.
     */
    public List<Claim> findStaleClaims(Instant olderThan, Instant notBefore) {
        return jdbc.sql("""
                        SELECT re.id, re.commitment_id, c.chat_id, re.body
                        FROM reminder_event re
                        JOIN commitment c ON c.id = re.commitment_id
                        WHERE re.sent_at IS NULL
                          AND re.claimed_at < :olderThan
                          AND re.claimed_at >= :notBefore
                        """)
                .param("olderThan", java.sql.Timestamp.from(olderThan))
                .param("notBefore", java.sql.Timestamp.from(notBefore))
                .query((rs, n) -> new Claim(
                        rs.getLong("id"), rs.getLong("commitment_id"),
                        rs.getLong("chat_id"), rs.getString("body")))
                .list();
    }

    /** Drops orphaned claims too old to be worth sending. */
    public int purgeAbandonedClaims(Instant before) {
        return jdbc.sql("DELETE FROM reminder_event WHERE sent_at IS NULL AND claimed_at < :before")
                .param("before", java.sql.Timestamp.from(before))
                .update();
    }
}
