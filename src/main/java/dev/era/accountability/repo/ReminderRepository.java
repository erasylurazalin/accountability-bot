package dev.era.accountability.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A reminder_event row is the schedule. A fire time is sampled once, written
 * here, and only then used: which is what lets a random schedule survive a
 * restart. Recomputing a sampled time after a crash would draw a different
 * number and send twice.
 */
@Repository
public class ReminderRepository {

    /** A reminder whose time has come but which nobody has claimed yet. */
    public record Due(long id, long deadlineId, long chatId) {}

    /** A claim interrupted between claiming and the Telegram call. */
    public record InFlight(long id, long chatId, long deadlineId, String body) {}

    private final JdbcClient jdbc;

    public ReminderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Persists a sampled fire time. Losing the race on the key means a
     * concurrent tick already scheduled this sequence number, so we drop ours
     * rather than schedule a near-duplicate.
     */
    public Optional<Long> schedule(long deadlineId, String idempotencyKey, int seq, Instant scheduledFor) {
        try {
            return jdbc.sql("""
                            INSERT INTO reminder_event (deadline_id, idempotency_key, seq, scheduled_for)
                            VALUES (:deadlineId, :key, :seq, :scheduledFor)
                            ON CONFLICT (idempotency_key) DO NOTHING
                            RETURNING id
                            """)
                    .param("deadlineId", deadlineId)
                    .param("key", idempotencyKey)
                    .param("seq", seq)
                    .param("scheduledFor", java.sql.Timestamp.from(scheduledFor))
                    .query(Long.class)
                    .optional();
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    public int nextSeq(long deadlineId) {
        Integer n = jdbc.sql("SELECT coalesce(max(seq) + 1, 0) FROM reminder_event WHERE deadline_id = :id")
                .param("id", deadlineId)
                .query(Integer.class)
                .single();
        return n == null ? 0 : n;
    }

    public List<Due> findDue(Instant now) {
        return jdbc.sql("""
                        SELECT re.id, re.deadline_id, d.chat_id
                        FROM reminder_event re
                        JOIN deadline d ON d.id = re.deadline_id
                        WHERE re.sent_at IS NULL
                          AND re.claimed_at IS NULL
                          AND re.scheduled_for <= :now
                          AND d.status = 'PENDING'
                        ORDER BY re.scheduled_for
                        """)
                .param("now", java.sql.Timestamp.from(now))
                .query((rs, n) -> new Due(rs.getLong("id"), rs.getLong("deadline_id"), rs.getLong("chat_id")))
                .list();
    }

    /**
     * Claims the right to send, and records what will be sent, in one statement.
     * Claim before send: a crash after this point leaves a claim the reaper
     * retries, whereas sending first can double-send, which is the worse
     * failure because duplicates train you to ignore the bot.
     */
    public boolean claim(long reminderEventId, String body, Instant at) {
        return jdbc.sql("""
                        UPDATE reminder_event SET claimed_at = :at, body = :body
                        WHERE id = :id AND claimed_at IS NULL AND sent_at IS NULL
                        """)
                .param("id", reminderEventId)
                .param("body", body)
                .param("at", java.sql.Timestamp.from(at))
                .update() > 0;
    }

    public void markSent(long reminderEventId, Long telegramMessageId, Instant at) {
        jdbc.sql("UPDATE reminder_event SET sent_at = :at, telegram_message_id = :mid WHERE id = :id")
                .param("id", reminderEventId)
                .param("mid", telegramMessageId)
                .param("at", java.sql.Timestamp.from(at))
                .update();
    }

    /**
     * Everything already sent or still queued for one deadline in a range.
     * Counting queued reminders too is what keeps the daily cap honest: a cap
     * that only counted delivered messages would let the sampler queue twenty.
     */
    public int countPlanned(long deadlineId, Instant from, Instant to) {
        Integer n = jdbc.sql("""
                        SELECT count(*) FROM reminder_event
                        WHERE deadline_id = :id
                          AND scheduled_for >= :from AND scheduled_for < :to
                        """)
                .param("id", deadlineId)
                .param("from", java.sql.Timestamp.from(from))
                .param("to", java.sql.Timestamp.from(to))
                .query(Integer.class)
                .single();
        return n == null ? 0 : n;
    }

    /**
     * The same count across every deadline a chat owns. Several deadlines
     * sampling independently will bury you, so the cap that matters is on the
     * person, not on each task.
     */
    public int countPlannedForChat(long chatId, Instant from, Instant to) {
        Integer n = jdbc.sql("""
                        SELECT count(*) FROM reminder_event re
                        JOIN deadline d ON d.id = re.deadline_id
                        WHERE d.chat_id = :chatId
                          AND re.scheduled_for >= :from AND re.scheduled_for < :to
                        """)
                .param("chatId", chatId)
                .param("from", java.sql.Timestamp.from(from))
                .param("to", java.sql.Timestamp.from(to))
                .query(Integer.class)
                .single();
        return n == null ? 0 : n;
    }

    /**
     * The end of one deadline's chain: its latest planned time, sent or not.
     * Each deadline draws from its own chain. Drawing from the person's latest
     * plan instead would let a distant deadline's draw, days ahead, block an
     * urgent one entirely.
     */
    public Optional<Instant> lastPlanned(long deadlineId) {
        return instantOrEmpty(jdbc.sql("SELECT max(scheduled_for) FROM reminder_event WHERE deadline_id = :id")
                .param("id", deadlineId));
    }

    /**
     * When this person last had a reminder claimed for sending. Minimum spacing
     * is enforced against the person at send time: a reminder too close to the
     * last one waits rather than being redrawn, so the rate stays honest.
     */
    public Optional<Instant> lastClaimedForChat(long chatId) {
        return instantOrEmpty(jdbc.sql("""
                        SELECT max(re.claimed_at) FROM reminder_event re
                        JOIN deadline d ON d.id = re.deadline_id
                        WHERE d.chat_id = :chatId
                        """)
                .param("chatId", chatId));
    }

    private static Optional<Instant> instantOrEmpty(JdbcClient.StatementSpec spec) {
        return spec.query((rs, n) -> {
                    var ts = rs.getTimestamp(1);
                    return ts == null ? null : ts.toInstant();
                })
                .optional()
                .filter(java.util.Objects::nonNull);
    }

    /**
     * Claims that were never sent: the process died between the claim and the
     * Telegram call. Retried only inside a grace window; an ancient orphan is a
     * reminder nobody wants any more.
     */
    public List<InFlight> findStaleClaims(Instant olderThan, Instant notBefore) {
        return jdbc.sql("""
                        SELECT re.id, d.chat_id, re.deadline_id, re.body
                        FROM reminder_event re
                        JOIN deadline d ON d.id = re.deadline_id
                        WHERE re.sent_at IS NULL
                          AND re.claimed_at IS NOT NULL
                          AND re.claimed_at < :olderThan
                          AND re.claimed_at >= :notBefore
                          AND re.body IS NOT NULL
                        """)
                .param("olderThan", java.sql.Timestamp.from(olderThan))
                .param("notBefore", java.sql.Timestamp.from(notBefore))
                .query((rs, n) -> new InFlight(
                        rs.getLong("id"), rs.getLong("chat_id"),
                        rs.getLong("deadline_id"), rs.getString("body")))
                .list();
    }

    /** Drops claims too old to be worth sending, and schedules nobody wants. */
    public int purgeAbandoned(Instant before) {
        return jdbc.sql("""
                        DELETE FROM reminder_event
                        WHERE sent_at IS NULL
                          AND coalesce(claimed_at, scheduled_for) < :before
                        """)
                .param("before", java.sql.Timestamp.from(before))
                .update();
    }

    /** Drops queued reminders for a deadline that has been settled or has lapsed. */
    public int cancelUnsentFor(long deadlineId) {
        return jdbc.sql("""
                        DELETE FROM reminder_event
                        WHERE deadline_id = :deadlineId AND sent_at IS NULL AND claimed_at IS NULL
                        """)
                .param("deadlineId", deadlineId)
                .update();
    }

    /**
     * Drops everything queued, for every deadline. Used when the tuning
     * changes: a draw can sit days ahead, and it was made with the old numbers.
     */
    public int cancelAllUnsent() {
        return jdbc.sql("DELETE FROM reminder_event WHERE sent_at IS NULL AND claimed_at IS NULL")
                .update();
    }
}
