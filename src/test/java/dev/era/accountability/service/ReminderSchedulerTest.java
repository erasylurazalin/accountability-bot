package dev.era.accountability.service;

import dev.era.accountability.TestClock;
import dev.era.accountability.repo.DeadlineRepository;
import dev.era.accountability.repo.ReminderRepository;
import dev.era.accountability.repo.UserSettingsRepository;
import dev.era.accountability.telegram.TelegramApi;
import dev.era.accountability.telegram.TelegramPoller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The real tick against a real Postgres, with time moved by hand.
 */
@SpringBootTest(properties = "bot.tick-initial-delay-ms=3600000")
@Testcontainers
class ReminderSchedulerTest {

    // 11:00 in Asia/Almaty, the default zone for a new user.
    private static final Instant START = Instant.parse("2026-10-01T06:00:00Z");
    private static final long CHAT = 1L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock(START);
        }
    }

    @MockitoBean TelegramApi telegram;
    @MockitoBean TelegramPoller poller;

    @Autowired TestClock clock;
    @Autowired JdbcClient jdbc;
    @Autowired ReminderScheduler scheduler;
    @Autowired DeadlineRepository deadlines;
    @Autowired UserSettingsRepository userSettings;
    @Autowired ReminderRepository reminders;
    @Autowired ReminderSampler sampler;
    @Autowired ReminderTuning tuning;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM reminder_event").update();
        jdbc.sql("DELETE FROM deadline").update();
        jdbc.sql("DELETE FROM user_settings").update();
        clock.set(START);

        // Nothing is planned until quiet hours are set. One minute at 03:00
        // keeps them out of the way.
        userSettings.ensureExists(CHAT);
        userSettings.setQuietHours(CHAT, LocalTime.of(3, 0), LocalTime.of(3, 1));

        when(telegram.sendMessage(anyLong(), anyString(), any())).thenReturn(Optional.of(42L));
    }

    @Test
    void aDeadlineAMonthOutGetsAboutHalfAReminderADayNotTheCap() {
        deadlines.create(CHAT, "far", START.plus(Duration.ofDays(30)));

        for (int i = 0; i < 3 * 24 * 12; i++) {
            scheduler.tick();
            clock.advance(Duration.ofMinutes(5));
        }

        // The curve gives about 0.5 a day, so about 1.5 over three days.
        // Redrawing rejected samples every tick hit the cap instead: 18.
        assertThat(sentCount()).isLessThanOrEqualTo(8);
    }

    @Test
    void aPlannedReminderSurvivesARestartAndIsSentOnce() {
        long id = deadlines.create(CHAT, "soon", START.plus(Duration.ofDays(2)));

        scheduler.tick();
        List<Instant> planned = plannedTimes(id);
        assertThat(planned).isNotEmpty();

        // A restart: a new scheduler with nothing in memory, same database.
        var restarted = new ReminderScheduler(deadlines, userSettings, reminders, sampler, tuning, telegram, clock);
        ReflectionTestUtils.setField(restarted, "claimRetryAfterSeconds", 120L);
        ReflectionTestUtils.setField(restarted, "claimAbandonAfterMinutes", 60L);

        restarted.tick();
        assertThat(plannedTimes(id)).isEqualTo(planned);

        clock.set(planned.get(0));
        restarted.tick();
        restarted.tick();

        verify(telegram, times(1)).sendMessage(eq(CHAT), anyString(), any());
        assertThat(sentCount()).isEqualTo(1);
    }

    @Test
    void twoRemindersInTheSameMinuteAreSpacedAtSendTime() {
        long a = deadlines.create(CHAT, "a", START.plus(Duration.ofDays(2)));
        long b = deadlines.create(CHAT, "b", START.plus(Duration.ofDays(2)));
        Instant t = START.plus(Duration.ofHours(1));
        reminders.schedule(a, "d%d:n0".formatted(a), 0, t);
        reminders.schedule(b, "d%d:n0".formatted(b), 0, t);

        clock.set(t);
        scheduler.tick();
        assertThat(sentCount()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(44));
        scheduler.tick();
        assertThat(sentCount()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(1));
        scheduler.tick();
        assertThat(sentCount()).isEqualTo(2);
    }

    private int sentCount() {
        return jdbc.sql("SELECT count(*) FROM reminder_event WHERE sent_at IS NOT NULL")
                .query(Integer.class).single();
    }

    private List<Instant> plannedTimes(long deadlineId) {
        return jdbc.sql("SELECT scheduled_for FROM reminder_event WHERE deadline_id = :id ORDER BY seq")
                .param("id", deadlineId)
                .query((rs, n) -> rs.getTimestamp(1).toInstant())
                .list();
    }
}
