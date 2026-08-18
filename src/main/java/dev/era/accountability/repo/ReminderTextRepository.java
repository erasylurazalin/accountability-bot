package dev.era.accountability.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class ReminderTextRepository {

    public record Text(long id, String body) {}

    private final JdbcClient jdbc;

    public ReminderTextRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes one unconsumed pre-generated line and marks it used in a single
     * statement, so two ticks can never hand out the same line.
     */
    public Optional<Text> consumeOne(long commitmentId, int urgencyTier) {
        return jdbc.sql("""
                        UPDATE reminder_text SET consumed_at = now()
                        WHERE id = (
                            SELECT id FROM reminder_text
                            WHERE commitment_id = :commitmentId
                              AND urgency_tier = :tier
                              AND consumed_at IS NULL
                            ORDER BY generated_at
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED
                        )
                        RETURNING id, body
                        """)
                .param("commitmentId", commitmentId)
                .param("tier", urgencyTier)
                .query((rs, n) -> new Text(rs.getLong("id"), rs.getString("body")))
                .optional();
    }

    public int countUnconsumed(long commitmentId) {
        Integer n = jdbc.sql("""
                        SELECT count(*) FROM reminder_text
                        WHERE commitment_id = :id AND consumed_at IS NULL
                        """)
                .param("id", commitmentId)
                .query(Integer.class)
                .single();
        return n == null ? 0 : n;
    }

    public void store(long commitmentId, int urgencyTier, String body, String generatedBy) {
        jdbc.sql("""
                        INSERT INTO reminder_text (commitment_id, urgency_tier, body, generated_by)
                        VALUES (:id, :tier, :body, :by)
                        """)
                .param("id", commitmentId)
                .param("tier", urgencyTier)
                .param("body", body)
                .param("by", generatedBy)
                .update();
    }
}
