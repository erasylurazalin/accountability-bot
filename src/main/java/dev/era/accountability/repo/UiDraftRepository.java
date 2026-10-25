package dev.era.accountability.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/**
 * The one place in this bot where a conversation has a state.
 *
 * Everything else is a single round trip: a button means what its callback_data
 * says, a command means what it says. Asking what a deadline is called breaks
 * that, because the answer is typed text with no context attached, so the
 * question has to be remembered somewhere.
 *
 * A draft goes cold after {@link #STALE_AFTER}, so an abandoned one expires
 * instead of resurfacing days later as a surprise. One row per chat: you can
 * only be inside one flow at a time, and starting another replaces it.
 */
@Repository
public class UiDraftRepository {

    /**
     * Long enough to answer, pick a date and pick a time without rushing, short
     * enough that a draft cannot come back from the dead.
     */
    public static final Duration STALE_AFTER = Duration.ofMinutes(30);

    /** What the bot is waiting for. */
    public enum Step {
        /** Waiting for the name of a new deadline. */
        NEW_NAME,
        /** Name known, waiting for a date tap. */
        NEW_DATE,
        /** Asked for a date in words. */
        NEW_DATE_TEXT,
        /** Date known, waiting for a time tap. */
        NEW_TIME,
        /** Asked for a time in words. */
        NEW_TIME_TEXT,
        /** Everything known, waiting for the confirm tap. */
        NEW_CONFIRM,
        /** Waiting for a new name for target_id. */
        EDIT_RENAME,
        /** Waiting for a new date for target_id. */
        EDIT_MOVE_DATE,
        /** Asked for that date in words. */
        EDIT_MOVE_DATE_TEXT,
        /** Waiting for a new time for target_id. */
        EDIT_MOVE_TIME,
        /** Asked for that time in words. */
        EDIT_MOVE_TIME_TEXT,
        /** Waiting for two clock times typed as HH:MM HH:MM. */
        QUIET_CUSTOM;

        /** Whether the next plain text message is an answer to the bot. */
        public boolean expectsText() {
            return switch (this) {
                case NEW_NAME, NEW_DATE_TEXT, NEW_TIME_TEXT,
                     EDIT_RENAME, EDIT_MOVE_DATE_TEXT, EDIT_MOVE_TIME_TEXT,
                     QUIET_CUSTOM -> true;
                default -> false;
            };
        }
    }

    public record Draft(long chatId, Step step, Long targetId, String name,
                        LocalDate dueDate, LocalTime dueTime, Long promptMessageId,
                        Instant updatedAt) {

        public boolean expectsText() {
            return step.expectsText();
        }

        /** Same draft, one step further on, leaving unanswered fields as they were. */
        public Draft at(Step next) {
            return new Draft(chatId, next, targetId, name, dueDate, dueTime, promptMessageId, Instant.now());
        }

        public Draft withPrompt(Long messageId) {
            return new Draft(chatId, step, targetId, name, dueDate, dueTime, messageId, Instant.now());
        }

        public Draft withName(String value) {
            return new Draft(chatId, step, targetId, value, dueDate, dueTime, promptMessageId, Instant.now());
        }

        public Draft withDate(LocalDate value) {
            return new Draft(chatId, step, targetId, name, value, dueTime, promptMessageId, Instant.now());
        }

        public Draft withTime(LocalTime value) {
            return new Draft(chatId, step, targetId, name, dueDate, value, promptMessageId, Instant.now());
        }
    }

    private final JdbcClient jdbc;

    public UiDraftRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Draft map(ResultSet rs, int rowNum) throws SQLException {
        Date date = rs.getDate("due_date");
        Time time = rs.getTime("due_time");
        return new Draft(
                rs.getLong("chat_id"),
                Step.valueOf(rs.getString("step")),
                (Long) rs.getObject("target_id"),
                rs.getString("name"),
                date == null ? null : date.toLocalDate(),
                time == null ? null : time.toLocalTime(),
                (Long) rs.getObject("prompt_message_id"),
                rs.getTimestamp("updated_at").toInstant());
    }

    /** The live draft for a chat, or empty if there is none or it has gone cold. */
    public Optional<Draft> find(long chatId) {
        purgeStale();
        return jdbc.sql("SELECT * FROM ui_draft WHERE chat_id = :chatId")
                .param("chatId", chatId)
                .query(UiDraftRepository::map)
                .optional();
    }

    public void save(Draft draft) {
        jdbc.sql("""
                        INSERT INTO ui_draft (chat_id, step, target_id, name, due_date, due_time,
                                              prompt_message_id, updated_at)
                        VALUES (:chatId, :step, :targetId, :name, :dueDate, :dueTime,
                                :promptMessageId, now())
                        ON CONFLICT (chat_id) DO UPDATE
                           SET step              = excluded.step,
                               target_id         = excluded.target_id,
                               name              = excluded.name,
                               due_date          = excluded.due_date,
                               due_time          = excluded.due_time,
                               prompt_message_id = excluded.prompt_message_id,
                               updated_at        = now()
                        """)
                .param("chatId", draft.chatId())
                .param("step", draft.step().name())
                .param("targetId", draft.targetId())
                .param("name", draft.name())
                .param("dueDate", draft.dueDate() == null ? null : Date.valueOf(draft.dueDate()))
                .param("dueTime", draft.dueTime() == null ? null : Time.valueOf(draft.dueTime()))
                .param("promptMessageId", draft.promptMessageId())
                .update();
    }

    public void clear(long chatId) {
        jdbc.sql("DELETE FROM ui_draft WHERE chat_id = :chatId")
                .param("chatId", chatId)
                .update();
    }

    /** Drops drafts nobody finished. Cheap enough to run on every read. */
    public int purgeStale() {
        return jdbc.sql("DELETE FROM ui_draft WHERE updated_at < :before")
                // JdbcClient cannot infer a type for Instant, so hand it a Timestamp.
                .param("before", java.sql.Timestamp.from(Instant.now().minus(STALE_AFTER)))
                .update();
    }
}
