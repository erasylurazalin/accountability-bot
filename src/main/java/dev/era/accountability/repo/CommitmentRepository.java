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
                rs.getTimestamp("created_at").toInstant());
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

    /** Every active commitment belonging to a chat that has settings. */
    public List<Long> activeChatIds() {
        return jdbc.sql("SELECT DISTINCT chat_id FROM commitment WHERE active")
                .query(Long.class)
                .list();
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
