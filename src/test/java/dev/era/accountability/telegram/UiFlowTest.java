package dev.era.accountability.telegram;

import dev.era.accountability.TestClock;
import dev.era.accountability.domain.Deadline;
import dev.era.accountability.repo.DeadlineRepository;
import dev.era.accountability.repo.AuditRepository;
import dev.era.accountability.repo.ReminderRepository;
import dev.era.accountability.repo.UiDraftRepository;
import dev.era.accountability.repo.UserSettingsRepository;
import dev.era.accountability.service.ReminderScheduler;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tap-driven UI against a real Postgres, with the clock moved by hand.
 *
 * What is worth testing here is not "does a button render" but the things that
 * are easy to get subtly wrong: the draft that survives a restart, the local
 * time a deadline actually lands on, a cancelled flow that leaves nothing
 * behind, and a retired command that still answers.
 */
@SpringBootTest(properties = "bot.tick-initial-delay-ms=3600000")
@Testcontainers
class UiFlowTest {

    /** 11:00 on 1 Oct 2026 in Asia/Almaty, the default zone. */
    private static final Instant START = Instant.parse("2026-10-01T06:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    private static final long CHAT = 1L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

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
    @Autowired UiRouter ui;
    @Autowired CommandRouter commands;
    @Autowired UiDraftRepository drafts;
    @Autowired DeadlineRepository deadlines;
    @Autowired UserSettingsRepository userSettings;
    @Autowired ReminderRepository reminders;
    @Autowired AuditRepository audit;
    @Autowired Screens screens;
    @Autowired QuietFlow quietFlow;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM reminder_event").update();
        jdbc.sql("DELETE FROM deadline").update();
        jdbc.sql("DELETE FROM user_settings").update();
        jdbc.sql("DELETE FROM ui_draft").update();
        clock.set(START);
        userSettings.ensureExists(CHAT);
    }

    // ------------------------------------------------------------------
    // Creating a deadline without typing a command
    // ------------------------------------------------------------------

    @Test
    void aDeadlineIsCreatedFromTapsAndOneTypedName() {
        // New deadline, then the name, then tomorrow, then 18:00, then Add it.
        ui.tap(CHAT, Keyboards.NEW, 10L);
        assertThat(drafts.find(CHAT)).get().extracting(UiDraftRepository.Draft::step)
                .isEqualTo(UiDraftRepository.Step.NEW_NAME);

        ui.text(CHAT, "hand in essay").orElseThrow();
        assertThat(drafts.find(CHAT)).get().extracting(UiDraftRepository.Draft::step)
                .isEqualTo(UiDraftRepository.Step.NEW_DATE);

        ui.tap(CHAT, Keyboards.NEW + ":d:2026-10-02", 11L);
        ui.tap(CHAT, Keyboards.NEW + ":t:1800", 12L);

        var created = ui.tap(CHAT, Keyboards.NEW + ":ok", 13L);
        assertThat(created.text()).contains("hand in essay");

        var saved = deadlines.findPendingByChat(CHAT);
        assertThat(saved).hasSize(1);
        // 18:00 on 2 Oct in Almaty (UTC+5) is 13:00Z, not 18:00Z.
        assertThat(saved.get(0).dueAt()).isEqualTo(Instant.parse("2026-10-02T13:00:00Z"));
        assertThat(drafts.find(CHAT)).isEmpty();
    }

    @Test
    void cancellingLeavesNoDeadlineAndNoDraft() {
        ui.tap(CHAT, Keyboards.NEW, 10L);
        ui.text(CHAT, "abandoned idea");
        ui.tap(CHAT, Keyboards.NEW + ":x:", 11L);

        assertThat(deadlines.findPendingByChat(CHAT)).isEmpty();
        assertThat(drafts.find(CHAT)).isEmpty();
    }

    @Test
    void aDraftSurvivesARestartBecauseItIsInPostgres() {
        ui.tap(CHAT, Keyboards.NEW, 10L);
        ui.text(CHAT, "half done");

        // A restart: new objects, nothing shared but the database.
        var restartedFlow = new DeadlineFlow(drafts, deadlines, reminders, userSettings, audit, clock);
        var restarted = new UiRouter(screens, restartedFlow, quietFlow, drafts, deadlines,
                reminders, audit, clock);
        restarted.tap(CHAT, Keyboards.NEW + ":d:2026-10-03", 11L);
        restarted.tap(CHAT, Keyboards.NEW + ":t:1200", 12L);
        restarted.tap(CHAT, Keyboards.NEW + ":ok", 13L);

        assertThat(deadlines.findPendingByChat(CHAT)).singleElement()
                .extracting(Deadline::name).isEqualTo("half done");
    }

    @Test
    void aTypedDateAndTimeStillWorkForAnythingThePickerDoesNotCover() {
        ui.tap(CHAT, Keyboards.NEW, 10L);
        ui.text(CHAT, "renew passport");
        ui.tap(CHAT, Keyboards.NEW + ":dx:", 11L);
        assertThat(drafts.find(CHAT)).get().extracting(UiDraftRepository.Draft::step)
                .isEqualTo(UiDraftRepository.Step.NEW_DATE_TEXT);

        ui.text(CHAT, "2027-03-15").orElseThrow();
        ui.tap(CHAT, Keyboards.NEW + ":tx:", 12L);
        ui.text(CHAT, "07:45").orElseThrow();
        ui.tap(CHAT, Keyboards.NEW + ":ok", 13L);

        assertThat(deadlines.findPendingByChat(CHAT)).singleElement()
                .extracting(d -> d.dueAt().atZone(ZONE).toLocalTime())
                .isEqualTo(LocalTime.of(7, 45));
    }

    @Test
    void theNameIsEscapedForHtml() {
        ui.tap(CHAT, Keyboards.NEW, 10L);
        ui.text(CHAT, "a < b & c");
        ui.tap(CHAT, Keyboards.NEW + ":d:2026-10-02", 11L);
        ui.tap(CHAT, Keyboards.NEW + ":t:2359", 12L);
        var confirm = ui.tap(CHAT, Keyboards.NEW + ":ok", 13L);

        assertThat(confirm.text()).contains("a &lt; b &amp; c");
    }

    @Test
    void backFromTheConfirmCardReturnsToTheTimesNotTheDates() {
        ui.tap(CHAT, Keyboards.NEW, 10L);
        ui.text(CHAT, "half way");
        ui.tap(CHAT, Keyboards.NEW + ":d:2026-10-02", 11L);
        ui.tap(CHAT, Keyboards.NEW + ":t:2359", 12L);

        var back = ui.tap(CHAT, Keyboards.NEW + ":b:", 13L);
        assertThat(back.text()).contains("What time?");
        assertThat(drafts.find(CHAT)).get().extracting(UiDraftRepository.Draft::step)
                .isEqualTo(UiDraftRepository.Step.NEW_TIME);
    }

    @Test
    void aDeadlineInThePastIsRefusedAtTheConfirmStep() {
        ui.tap(CHAT, Keyboards.NEW, 10L);
        ui.text(CHAT, "too late");
        // Today at 09:00 is before the clock's 11:00.
        ui.tap(CHAT, Keyboards.NEW + ":d:2026-10-01", 11L);
        ui.tap(CHAT, Keyboards.NEW + ":t:0900", 12L);
        var result = ui.tap(CHAT, Keyboards.NEW + ":ok", 13L);

        assertThat(result.text()).contains("already passed");
        assertThat(deadlines.findPendingByChat(CHAT)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Quiet hours, which is the switch that decides whether anything is sent
    // ------------------------------------------------------------------

    @Test
    void aQuietHoursPresetTurnsRemindersOn() {
        ui.tap(CHAT, Keyboards.QUIET, 10L);
        ui.tap(CHAT, Keyboards.QUIET + ":p:2300-0700", 11L);

        var settings = userSettings.find(CHAT).orElseThrow();
        assertThat(settings.quietHoursStart()).isEqualTo(LocalTime.of(23, 0));
        assertThat(settings.quietHoursEnd()).isEqualTo(LocalTime.of(7, 0));
        assertThat(settings.quietHoursSet()).isTrue();
    }

    @Test
    void turningRemindersOffClearsQuietHoursRatherThanSettingThemToMidnight() {
        ui.tap(CHAT, Keyboards.QUIET + ":p:2200-0800", 10L);
        ui.tap(CHAT, Keyboards.QUIET + ":off", 11L);

        assertThat(userSettings.find(CHAT).orElseThrow().quietHoursSet()).isFalse();
    }

    @Test
    void aCustomWindowIsTypedAndValidated() {
        ui.tap(CHAT, Keyboards.QUIET + ":c", 10L);
        ui.text(CHAT, "01:30 09:15").orElseThrow();

        assertThat(userSettings.find(CHAT).orElseThrow().quietHoursStart()).isEqualTo(LocalTime.of(1, 30));

        // The same time on both sides is refused: no quiet hours is the off
        // switch, not an all-day silence.
        var again = ui.tap(CHAT, Keyboards.QUIET + ":c", 11L);
        assertThat(again.text()).contains("Send two times");
        var refused = ui.text(CHAT, "05:00 05:00").orElseThrow();
        assertThat(refused.alert()).isTrue();
        assertThat(userSettings.find(CHAT).orElseThrow().quietHoursStart()).isEqualTo(LocalTime.of(1, 30));
    }

    // ------------------------------------------------------------------
    // Acting on a deadline
    // ------------------------------------------------------------------

    @Test
    void doneAndRemoveBothEndAtANewMessageWithNoButtons() {
        long id = deadlines.create(CHAT, "coursework", START.plus(Duration.ofDays(1)));

        var done = ui.tap(CHAT, Keyboards.DONE + ":" + id, 10L);
        assertThat(done.text()).contains("Done");
        assertThat(deadlines.find(id, CHAT).orElseThrow().status()).isEqualTo(Deadline.DONE);

        long other = deadlines.create(CHAT, "draft", START.plus(Duration.ofDays(2)));
        ui.tap(CHAT, Keyboards.REMOVE + ":" + other, 11L);
        var removed = ui.tap(CHAT, Keyboards.CONFIRM + ":" + other, 12L);
        assertThat(removed.text()).contains("Removed");
        assertThat(deadlines.find(other, CHAT)).isEmpty();
    }

    @Test
    void aStaleButtonOnADeletedDeadlineAnswersWithAToast() {
        long id = deadlines.create(CHAT, "gone soon", START.plus(Duration.ofDays(1)));
        deadlines.remove(id, CHAT);

        var result = ui.tap(CHAT, Keyboards.DONE + ":" + id, 10L);
        assertThat(result.mode()).isEqualTo(UiAction.Mode.NOTHING);
        assertThat(result.toast()).isEqualTo("That one is gone.");
    }

    @Test
    void movingADeadlineRedrawsItsReminders() {
        long id = deadlines.create(CHAT, "essay", START.plus(Duration.ofDays(1)));
        reminders.schedule(id, "d%d:n0".formatted(id), 0, START.plus(Duration.ofHours(5)));

        ui.tap(CHAT, Keyboards.EDIT + ":m:" + id, 10L);
        ui.tap(CHAT, Keyboards.EDIT + ":d:2026-10-09", 11L);
        ui.tap(CHAT, Keyboards.EDIT + ":t:1200", 12L);

        assertThat(deadlines.find(id, CHAT).orElseThrow().dueAt())
                .isEqualTo(Instant.parse("2026-10-09T07:00:00Z"));
        assertThat(unsentReminders(id)).isZero();
    }

    @Test
    void renamingIsTypedAndRewritesTheCard() {
        long id = deadlines.create(CHAT, "old name", START.plus(Duration.ofDays(1)));
        ui.tap(CHAT, Keyboards.EDIT + ":r:" + id, 10L);
        var renamed = ui.text(CHAT, "new name").orElseThrow();

        assertThat(renamed.text()).contains("new name");
        assertThat(deadlines.find(id, CHAT).orElseThrow().name()).isEqualTo("new name");
    }

    // ------------------------------------------------------------------
    // The screens and the leftovers
    // ------------------------------------------------------------------

    @Test
    void theListGroupsByWhenAndOneButtonOpensEachDeadline() {
        long today = deadlines.create(CHAT, "today thing", START.plus(Duration.ofHours(2)));
        deadlines.create(CHAT, "tomorrow thing", START.plus(Duration.ofDays(1)));

        var list = ui.tap(CHAT, Keyboards.LIST, 10L);
        assertThat(list.text()).contains("<b>Today</b>", "tomorrow thing");

        var card = ui.tap(CHAT, Keyboards.VIEW + ":" + today, 11L);
        assertThat(card.text()).contains("today thing");
        assertThat(card.mode()).isEqualTo(UiAction.Mode.REPLACE);
    }

    @Test
    void theMenuSaysRemindersAreOffUntilQuietHoursAreSet() {
        var before = ui.tap(CHAT, Keyboards.MENU, 10L);
        assertThat(before.text()).contains("Reminders are off");

        ui.tap(CHAT, Keyboards.QUIET + ":p:2300-0700", 11L);

        var after = ui.tap(CHAT, Keyboards.MENU, 12L);
        assertThat(after.text()).doesNotContain("Reminders are off");
    }

    @Test
    void aRetiredCommandPointsAtTheButtonsInsteadOfPrintingUsage() {
        var answer = commands.handle(CHAT, "/new tomorrow 23:59 essay");
        assertThat(answer.text()).contains("That is a button now");
    }

    @Test
    void listStillWorksTyped() {
        deadlines.create(CHAT, "typed list", START.plus(Duration.ofHours(3)));
        assertThat(commands.handle(CHAT, "/list").text()).contains("typed list");
    }

    @Test
    void anAnswerWithNoQuestionOpenIsTreatedAsACommand() {
        // Nothing is waiting, so this must not be swallowed by a flow.
        assertThat(ui.text(CHAT, "just some words")).isEmpty();
    }

    private int unsentReminders(long deadlineId) {
        return jdbc.sql("SELECT count(*) FROM reminder_event WHERE deadline_id = :id AND sent_at IS NULL")
                .param("id", deadlineId)
                .query(Integer.class)
                .single();
    }
}
