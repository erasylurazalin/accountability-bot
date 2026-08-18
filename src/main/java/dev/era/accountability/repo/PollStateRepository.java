package dev.era.accountability.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persisting the long-poll offset is what stops a restart from replaying every
 * update Telegram still has buffered (it retains them for 24 h).
 */
@Repository
public class PollStateRepository {

    private final JdbcClient jdbc;

    public PollStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long lastUpdateId() {
        Long v = jdbc.sql("SELECT last_update_id FROM poll_state WHERE id = 1")
                .query(Long.class)
                .single();
        return v == null ? 0L : v;
    }

    /** Monotonic: a late writer can never move the offset backwards. */
    public void advanceTo(long updateId) {
        jdbc.sql("UPDATE poll_state SET last_update_id = :v WHERE id = 1 AND last_update_id < :v")
                .param("v", updateId)
                .update();
    }
}
