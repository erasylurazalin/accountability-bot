package dev.era.accountability.repo;

import dev.era.accountability.domain.Commitment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class CommitmentRepository {

    private final JdbcClient jdbc;

    public CommitmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Commitment map(ResultSet rs, int rowNum) throws SQLException {
        return new Commitment(
                rs.getLong("id"),
                rs.getLong("chat_id"),
                rs.getString("name"),
                rs.getString("type"),
                rs.getBoolean("active"),
                rs.getString("timezone"),
                rs.getObject("window_start", java.time.LocalTime.class),
                rs.getObject("window_end", java.time.LocalTime.class),
                rs.getObject("quiet_hours_start", java.time.LocalTime.class),
                rs.getObject("quiet_hours_end", java.time.LocalTime.class),
                rs.getInt("reminder_interval_minutes"),
                rs.getInt("max_reminders_per_day"));
    }

    public long create(long chatId, String name) {
        return jdbc.sql("""
                        INSERT INTO commitment (chat_id, name, type)
                        VALUES (:chatId, :name, 'HABIT')
                        RETURNING id
                        """)
                .param("chatId", chatId)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    public List<Commitment> findActive() {
        return jdbc.sql("SELECT * FROM commitment WHERE active ORDER BY id")
                .query(CommitmentRepository::map)
                .list();
    }

    public List<Commitment> findActiveByChat(long chatId) {
        return jdbc.sql("SELECT * FROM commitment WHERE active AND chat_id = :chatId ORDER BY id")
                .param("chatId", chatId)
                .query(CommitmentRepository::map)
                .list();
    }

    public Optional<Commitment> findByIdAndChat(long id, long chatId) {
        return jdbc.sql("SELECT * FROM commitment WHERE id = :id AND chat_id = :chatId")
                .param("id", id)
                .param("chatId", chatId)
                .query(CommitmentRepository::map)
                .optional();
    }

    public boolean deactivate(long id, long chatId) {
        return jdbc.sql("UPDATE commitment SET active = FALSE WHERE id = :id AND chat_id = :chatId AND active")
                .param("id", id)
                .param("chatId", chatId)
                .update() > 0;
    }
}
