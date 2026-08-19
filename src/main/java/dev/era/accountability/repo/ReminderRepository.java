package dev.era.accountability.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
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
    public record Due(long id, long commitmentId, long checkInId, long chatId,
                      LocalDate localDate, Instant dueAt) {}

    /** A claim interrupted between claiming and the Telegram call. */
    public record InFlight(long id, long chatId, String body) {}

    private final JdbcClient jdbc;

    public ReminderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Persists a sampled fire time. Losing the race on the key means a
     * concurrent tick already scheduled this sequence number, so we drop ours
     * rather than schedule a near-duplicate.
     */
    public Optional<Long> schedule(long commitmentId, long checkInId, String idempotencyKey,
                                   int seq, Instant scheduledFor) {
        try {
            return jdbc.sql("""
                            INSERT INTO reminder_event
                                (commitment_id, check_in_id, idempotency_key, seq, scheduled_for)
                            VALUES (:commitmentId, :checkInId, :key, :seq, :scheduledFor)
                            ON CONFLICT (idempotency_key) DO NOTHING
                            RETURNING id
                            """)
                    .param("commitmentId", commitmentId)
                    .param("checkInId", checkInId)
                    .param("key", idempotencyKey)
                    .param("seq", seq)
                    .param("scheduledFor", java.sql.Timestamp.from(scheduledFor))
                    .query(Long.class)
                    .optional();
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    /** Next per-day sequence number, which is what the idempotency key encodes. */
    public int nextSeq(long commitmentId, LocalDate localDate) {
        Integer n = jdbc.sql("""
                        SELECT coalesce(max(seq) + 1, 0) FROM reminder_event
                        WHERE commitment_id = :id AND idempotency_key LIKE :prefix
                        """)
                .param("id", commitmentId)
                .param("prefix", "c%d:%s:%%".formatted(commitmentId, localDate))
                .query(Integer.class)
                .single();
        return n == null ? 0 : n;
    }

    public List<Due> findDue(Instant now) {
        return jdbc.sql("""
                        SELECT re.id, re.commitment_id, re.check_in_id, c.chat_id,
                               ci.local_date, ci.due_at
                        FROM reminder_event re
                        JOIN commitment c ON c.id = re.commitment_id
                        JOIN check_in ci  ON ci.id = re.check_in_id
                        WHERE re.sent_at IS NULL
                          AND re.claimed_at IS NULL
                          AND re.scheduled_for <= :now
                          AND ci.status = 'PENDING'
                        ORDER BY re.scheduled_for
                        """)
                .param("now", java.sql.Timestamp.from(now))
                .query((rs, n) -> new Due(
                        rs.getLong("id"), rs.getLong("commitment_id"), rs.getLong("check_in_id"),
                        rs.getLong("chat_id"), rs.getObject("local_date", LocalDate.class),
                        rs.getTimestamp("due_at").toInstant()))
                .list();
    }

    /**
     * Claims the right to send, and records what will be sent, in one statement.
     * Claim before send is CLAUDE.md invariant 1: a crash after this point
     * leaves a claim the reaper retries, whereas sending first can double-send,
     * which is the worse failure because it trains you to ignore the bot.
     */
    public boolean claim(long reminderEventId, String body) {
        return jdbc.sql("""
                        UPDATE reminder_event SET claimed_at = now(), body = :body
                        WHERE id = :id AND claimed_at IS NULL AND sent_at IS NULL
                        """)
                .param("id", reminderEventId)
                .param("body", body)
                .update() > 0;
    }

    public void markSent(long reminderEventId, Long telegramMessageId) {
        jdbc.sql("UPDATE reminder_event SET sent_at = now(), telegram_message_id = :mid WHERE id = :id")
                .param("id", reminderEventId)
                .param("mid", telegramMessageId)
                .update();
    }

    /** Reminders actually delivered to a commitment inside a time range. */
    public int countSent(long commitmentId, Instant from, Instant to) {
        Integer n = jdbc.sql("""
                        SELECT count(*) FROM reminder_event
                        WHERE commitment_id = :id AND sent_at >= :from AND sent_at < :to
                        """)
                .param("id", commitmentId)
                .param("from", java.sql.Timestamp.from(from))
                .param("to", java.sql.Timestamp.from(to))
                .query(Integer.class)
                .single();
        return n == null ? 0 : n;
    }

    /**
     * Everything already sent or still queued for one commitment in a range.
     * Counting queued reminders too is what keeps the daily cap honest: a cap
     * that only counted delivered messages would let the sampler queue twenty.
     */
    public int countPlanned(long commitmentId, Instant from, Instant to) {
        Integer n = jdbc.sql("""
                        SELECT count(*) FROM reminder_event
                        WHERE commitment_id = :id
                          AND scheduled_for >= :from AND scheduled_for < :to
                        """)
                .param("id", commitmentId)
                .param("from", java.sql.Timestamp.from(from))
                .param("to", java.sql.Timestamp.from(to))
                .query(Integer.class)
                .single();
        return n == null ? 0 : n;
    }

    /**
     * The same count across every commitment a chat owns. NOTES.md section 4.2
     * calls this the week-two bug: independent samplers per task will bury you,
     * so the cap that matters is on the person.
     */
    public int countPlannedForChat(long chatId, Instant from, Instant to) {
        Integer n = jdbc.sql("""
                        SELECT count(*) FROM reminder_event re
                        JOIN commitment c ON c.id = re.commitment_id
                        WHERE c.chat_id = :chatId
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
     * Latest fire time already planned for anyone in this chat. Minimum spacing
     * is enforced against the person for the same reason the daily cap is.
     */
    public Optional<Instant> lastPlannedForChat(long chatId, Instant notBefore) {
        return jdbc.sql("""
                        SELECT max(re.scheduled_for) FROM reminder_event re
                        JOIN commitment c ON c.id = re.commitment_id
                        WHERE c.chat_id = :chatId AND re.scheduled_for >= :notBefore
                        """)
                .param("chatId", chatId)
                .param("notBefore", java.sql.Timestamp.from(notBefore))
                .query((rs, n) -> {
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
                        SELECT re.id, c.chat_id, re.body
                        FROM reminder_event re
                        JOIN commitment c ON c.id = re.commitment_id
                        WHERE re.sent_at IS NULL
                          AND re.claimed_at IS NOT NULL
                          AND re.claimed_at < :olderThan
                          AND re.claimed_at >= :notBefore
                          AND re.body IS NOT NULL
                        """)
                .param("olderThan", java.sql.Timestamp.from(olderThan))
                .param("notBefore", java.sql.Timestamp.from(notBefore))
                .query((rs, n) -> new InFlight(
                        rs.getLong("id"), rs.getLong("chat_id"), rs.getString("body")))
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

    /** Drops queued reminders for a day that has been settled or has closed. */
    public int cancelUnsentFor(long checkInId) {
        return jdbc.sql("""
                        DELETE FROM reminder_event
                        WHERE check_in_id = :checkInId AND sent_at IS NULL AND claimed_at IS NULL
                        """)
                .param("checkInId", checkInId)
                .update();
    }
}
