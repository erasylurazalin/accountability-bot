package dev.era.accountability.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The single reminder_tuning row. Every column is nullable and null means "no
 * override": the value in application.yml wins. That is what lets a reset be
 * one UPDATE rather than a hunt for the original numbers.
 */
@Repository
public class ReminderTuningRepository {

    /** Keys /lambda accepts, in the order it prints them. */
    public static final Map<String, String> COLUMNS = new LinkedHashMap<>(Map.of());
    static {
        COLUMNS.put("base",    "lambda_base");
        COLUMNS.put("min",     "lambda_min");
        COLUMNS.put("max",     "lambda_max");
        COLUMNS.put("spacing", "min_spacing_minutes");
        COLUMNS.put("cap",     "max_per_deadline_daily");
        COLUMNS.put("usercap", "max_per_user_daily");
        COLUMNS.put("ahead",   "schedule_ahead_minutes");
    }

    public record Overrides(Double lambdaBase, Double lambdaMin, Double lambdaMax,
                            Integer minSpacingMinutes, Integer maxPerDeadlineDaily,
                            Integer maxPerUserDaily, Integer scheduleAheadMinutes) {
        public static Overrides none() {
            return new Overrides(null, null, null, null, null, null, null);
        }
    }

    private final JdbcClient jdbc;

    public ReminderTuningRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Overrides> load() {
        return jdbc.sql("SELECT * FROM reminder_tuning WHERE id = 1")
                .query((rs, n) -> new Overrides(
                        (Double) rs.getObject("lambda_base"),
                        (Double) rs.getObject("lambda_min"),
                        (Double) rs.getObject("lambda_max"),
                        (Integer) rs.getObject("min_spacing_minutes"),
                        (Integer) rs.getObject("max_per_deadline_daily"),
                        (Integer) rs.getObject("max_per_user_daily"),
                        (Integer) rs.getObject("schedule_ahead_minutes")))
                .optional();
    }

    /**
     * Sets one override. The column name comes from {@link #COLUMNS} and never
     * from user input, so the string concatenation cannot be injected into.
     */
    public void set(String key, Number value) {
        String column = COLUMNS.get(key);
        if (column == null) {
            throw new IllegalArgumentException("unknown tuning key: " + key);
        }
        jdbc.sql("UPDATE reminder_tuning SET " + column + " = :v, updated_at = now() WHERE id = 1")
                .param("v", value)
                .update();
    }

    /** Drops every override, handing authority back to application.yml. */
    public void reset() {
        jdbc.sql("""
                UPDATE reminder_tuning SET
                    lambda_base = NULL, lambda_min = NULL, lambda_max = NULL,
                    min_spacing_minutes = NULL, max_per_deadline_daily = NULL,
                    max_per_user_daily = NULL, schedule_ahead_minutes = NULL,
                    updated_at = now()
                WHERE id = 1
                """).update();
    }
}
