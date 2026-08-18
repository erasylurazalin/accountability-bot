package dev.era.accountability.repo;

import dev.era.accountability.domain.StreakInfo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public class StreakRepository {

    private final JdbcClient jdbc;

    public StreakRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StreakInfo> find(long commitmentId) {
        return jdbc.sql("SELECT * FROM streak WHERE commitment_id = :id")
                .param("id", commitmentId)
                .query((rs, n) -> new StreakInfo(
                        rs.getLong("commitment_id"),
                        rs.getInt("current_len"),
                        rs.getInt("best_len"),
                        rs.getObject("last_success_on", LocalDate.class)))
                .optional();
    }

    /**
     * A success on {@code day} extends the streak when the previous success was
     * the day before, and restarts it at 1 otherwise. Re-recording the same day
     * is a no-op, which keeps the operation safe to retry.
     */
    public void recordSuccess(long commitmentId, LocalDate day) {
        jdbc.sql("""
                        INSERT INTO streak (commitment_id, current_len, best_len, last_success_on)
                        VALUES (:id, 1, 1, :day)
                        ON CONFLICT (commitment_id) DO UPDATE SET
                            current_len = CASE
                                WHEN streak.last_success_on = :day THEN streak.current_len
                                WHEN streak.last_success_on = :day - 1 THEN streak.current_len + 1
                                ELSE 1
                            END,
                            last_success_on = :day
                        """)
                .param("id", commitmentId)
                .param("day", day)
                .update();

        jdbc.sql("UPDATE streak SET best_len = current_len WHERE commitment_id = :id AND current_len > best_len")
                .param("id", commitmentId)
                .update();
    }

    /** A skip or an expiry breaks the streak; the best length is preserved. */
    public void breakStreak(long commitmentId) {
        jdbc.sql("""
                        INSERT INTO streak (commitment_id, current_len, best_len, last_success_on)
                        VALUES (:id, 0, 0, NULL)
                        ON CONFLICT (commitment_id) DO UPDATE SET current_len = 0
                        """)
                .param("id", commitmentId)
                .update();
    }
}
