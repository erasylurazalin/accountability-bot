package dev.era.accountability.repo;

import dev.era.accountability.domain.UserSettings;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.Optional;

@Repository
public class UserSettingsRepository {

    private final JdbcClient jdbc;

    public UserSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static UserSettings map(ResultSet rs, int rowNum) throws SQLException {
        return new UserSettings(
                rs.getLong("chat_id"),
                rs.getString("timezone"),
                rs.getObject("quiet_hours_start", LocalTime.class),
                rs.getObject("quiet_hours_end", LocalTime.class));
    }

    public Optional<UserSettings> find(long chatId) {
        return jdbc.sql("SELECT * FROM user_settings WHERE chat_id = :chatId")
                .param("chatId", chatId)
                .query(UserSettingsRepository::map)
                .optional();
    }

    /**
     * Creates the row if it is missing, leaving quiet hours NULL. Called
     * whenever a commitment is created so the scheduler always has a timezone,
     * while "quiet hours not chosen yet" stays visible as a distinct state.
     */
    public void ensureExists(long chatId) {
        jdbc.sql("""
                        INSERT INTO user_settings (chat_id) VALUES (:chatId)
                        ON CONFLICT (chat_id) DO NOTHING
                        """)
                .param("chatId", chatId)
                .update();
    }

    public void setQuietHours(long chatId, LocalTime start, LocalTime end) {
        ensureExists(chatId);
        jdbc.sql("""
                        UPDATE user_settings
                           SET quiet_hours_start = :start,
                               quiet_hours_end   = :end,
                               updated_at        = now()
                         WHERE chat_id = :chatId
                        """)
                .param("chatId", chatId)
                .param("start", start)
                .param("end", end)
                .update();
    }

    public void setTimezone(long chatId, String timezone) {
        ensureExists(chatId);
        jdbc.sql("UPDATE user_settings SET timezone = :tz, updated_at = now() WHERE chat_id = :chatId")
                .param("chatId", chatId)
                .param("tz", timezone)
                .update();
    }
}
