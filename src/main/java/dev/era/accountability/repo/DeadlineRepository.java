package dev.era.accountability.repo;

import dev.era.accountability.domain.Deadline;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class DeadlineRepository {

    private final JdbcClient jdbc;

    public DeadlineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Deadline map(ResultSet rs, int rowNum) throws SQLException {
        return new Deadline(
                rs.getLong("id"),
                rs.getLong("chat_id"),
                rs.getString("name"),
                rs.getTimestamp("due_at").toInstant(),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }

    public long create(long chatId, String name, Instant dueAt) {
        return jdbc.sql("""
                        INSERT INTO deadline (chat_id, name, due_at)
                        VALUES (:chatId, :name, :dueAt)
                        RETURNING id
                        """)
                .param("chatId", chatId)
                .param("name", name)
                .param("dueAt", java.sql.Timestamp.from(dueAt))
                .query(Long.class)
                .single();
    }

    /** Every live deadline, across all chats. The scheduler's input. */
    public List<Deadline> findPending() {
        return jdbc.sql("SELECT * FROM deadline WHERE status = 'PENDING' ORDER BY due_at")
                .query(DeadlineRepository::map)
                .list();
    }

    public List<Deadline> findPendingByChat(long chatId) {
        return jdbc.sql("""
                        SELECT * FROM deadline
                        WHERE chat_id = :chatId AND status = 'PENDING'
                        ORDER BY due_at
                        """)
                .param("chatId", chatId)
                .query(DeadlineRepository::map)
                .list();
    }

    /** Recently lapsed, so /list can say a deadline went past rather than vanishing. */
    public List<Deadline> findRecentlyExpired(long chatId, Instant since) {
        return jdbc.sql("""
                        SELECT * FROM deadline
                        WHERE chat_id = :chatId AND status = 'EXPIRED' AND due_at >= :since
                        ORDER BY due_at DESC
                        """)
                .param("chatId", chatId)
                .param("since", java.sql.Timestamp.from(since))
                .query(DeadlineRepository::map)
                .list();
    }

    public Optional<Deadline> find(long id, long chatId) {
        return jdbc.sql("SELECT * FROM deadline WHERE id = :id AND chat_id = :chatId")
                .param("id", id)
                .param("chatId", chatId)
                .query(DeadlineRepository::map)
                .optional();
    }

    /** Deadlines whose moment has passed and which nobody settled. */
    public List<Deadline> findLapsed(Instant now) {
        return jdbc.sql("SELECT * FROM deadline WHERE status = 'PENDING' AND due_at <= :now")
                .param("now", java.sql.Timestamp.from(now))
                .query(DeadlineRepository::map)
                .list();
    }

    public boolean markExpired(long id) {
        return jdbc.sql("""
                        UPDATE deadline SET status = 'EXPIRED', updated_at = now()
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", id)
                .update() > 0;
    }

    /**
     * Finished. Kept rather than deleted, so there is a record of what actually
     * got done; /remove is the one that deletes.
     */
    public boolean markDone(long id, long chatId) {
        return jdbc.sql("""
                        UPDATE deadline SET status = 'DONE', updated_at = now()
                        WHERE id = :id AND chat_id = :chatId AND status = 'PENDING'
                        """)
                .param("id", id)
                .param("chatId", chatId)
                .update() > 0;
    }

    public boolean rename(long id, long chatId, String name) {
        return jdbc.sql("""
                        UPDATE deadline SET name = :name, updated_at = now()
                        WHERE id = :id AND chat_id = :chatId
                        """)
                .param("id", id)
                .param("chatId", chatId)
                .param("name", name)
                .update() > 0;
    }

    /**
     * Moves the due time. Reopens a lapsed deadline, since pushing a date back
     * is the normal way to say "this slipped, it is still live".
     */
    public boolean reschedule(long id, long chatId, Instant dueAt) {
        return jdbc.sql("""
                        UPDATE deadline
                        SET due_at = :dueAt, status = 'PENDING', updated_at = now()
                        WHERE id = :id AND chat_id = :chatId
                        """)
                .param("id", id)
                .param("chatId", chatId)
                .param("dueAt", java.sql.Timestamp.from(dueAt))
                .update() > 0;
    }

    /** Hard delete. reminder_event cascades, so no queued reminder outlives it. */
    public boolean remove(long id, long chatId) {
        return jdbc.sql("DELETE FROM deadline WHERE id = :id AND chat_id = :chatId")
                .param("id", id)
                .param("chatId", chatId)
                .update() > 0;
    }
}
