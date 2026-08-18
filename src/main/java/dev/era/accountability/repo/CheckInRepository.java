package dev.era.accountability.repo;

import dev.era.accountability.domain.CheckIn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class CheckInRepository {

    private final JdbcClient jdbc;

    public CheckInRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static CheckIn map(ResultSet rs, int rowNum) throws SQLException {
        var completed = rs.getTimestamp("completed_at");
        return new CheckIn(
                rs.getLong("id"),
                rs.getLong("commitment_id"),
                rs.getObject("local_date", LocalDate.class),
                rs.getTimestamp("due_window_start").toInstant(),
                rs.getTimestamp("due_window_end").toInstant(),
                rs.getString("status"),
                completed == null ? null : completed.toInstant(),
                rs.getString("excuse_text"));
    }

    /**
     * Materialises the window for a day. ON CONFLICT DO NOTHING makes this safe
     * to call from every tick and across restarts — the unique constraint on
     * (commitment_id, local_date) is the actual guarantee.
     */
    public void ensureWindow(long commitmentId, LocalDate localDate, Instant start, Instant end) {
        jdbc.sql("""
                        INSERT INTO check_in (commitment_id, local_date, due_window_start, due_window_end)
                        VALUES (:commitmentId, :localDate, :start, :end)
                        ON CONFLICT (commitment_id, local_date) DO NOTHING
                        """)
                .param("commitmentId", commitmentId)
                .param("localDate", localDate)
                .param("start", java.sql.Timestamp.from(start))
                .param("end", java.sql.Timestamp.from(end))
                .update();
    }

    public Optional<CheckIn> findOpen(long commitmentId) {
        return jdbc.sql("""
                        SELECT * FROM check_in
                        WHERE commitment_id = :commitmentId AND status = 'PENDING'
                        ORDER BY due_window_start
                        LIMIT 1
                        """)
                .param("commitmentId", commitmentId)
                .query(CheckInRepository::map)
                .optional();
    }

    public List<CheckIn> findPendingInWindow(Instant now) {
        return jdbc.sql("""
                        SELECT * FROM check_in
                        WHERE status = 'PENDING'
                          AND due_window_start <= :now
                          AND due_window_end > :now
                        """)
                .param("now", java.sql.Timestamp.from(now))
                .query(CheckInRepository::map)
                .list();
    }

    public List<CheckIn> findExpirable(Instant now) {
        return jdbc.sql("SELECT * FROM check_in WHERE status = 'PENDING' AND due_window_end <= :now")
                .param("now", java.sql.Timestamp.from(now))
                .query(CheckInRepository::map)
                .list();
    }

    public boolean markDone(long checkInId) {
        return jdbc.sql("""
                        UPDATE check_in SET status = 'DONE', completed_at = now()
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", checkInId)
                .update() > 0;
    }

    public boolean markSkipped(long checkInId, String excuse) {
        return jdbc.sql("""
                        UPDATE check_in SET status = 'SKIPPED', completed_at = now(), excuse_text = :excuse
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", checkInId)
                .param("excuse", excuse)
                .update() > 0;
    }

    public boolean markExpired(long checkInId) {
        return jdbc.sql("UPDATE check_in SET status = 'EXPIRED' WHERE id = :id AND status = 'PENDING'")
                .param("id", checkInId)
                .update() > 0;
    }

    public List<String> recentExcuses(long commitmentId, int limit) {
        return jdbc.sql("""
                        SELECT excuse_text FROM check_in
                        WHERE commitment_id = :commitmentId AND status = 'SKIPPED'
                        ORDER BY completed_at DESC LIMIT :limit
                        """)
                .param("commitmentId", commitmentId)
                .param("limit", limit)
                .query(String.class)
                .list();
    }
}
