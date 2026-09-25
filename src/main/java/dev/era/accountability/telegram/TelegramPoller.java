package dev.era.accountability.telegram;

import dev.era.accountability.config.BotProperties;
import dev.era.accountability.repo.PollStateRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Long-poll loop on a dedicated thread. Telegram holds the connection open, so
 * the bot dials out and nothing has to be exposed inbound: which is what makes
 * hosting behind the flat's double NAT possible at all.
 */
@Component
public class TelegramPoller implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TelegramPoller.class);
    private static final long MAX_BACKOFF_MS = 60_000L;

    private final TelegramApi api;
    private final CommandRouter router;
    private final CallbackRouter callbacks;
    private final PollStateRepository pollState;
    private final BotProperties props;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread thread;

    public TelegramPoller(TelegramApi api, CommandRouter router, CallbackRouter callbacks,
                          PollStateRepository pollState, BotProperties props) {
        this.api = api;
        this.router = router;
        this.callbacks = callbacks;
        this.pollState = pollState;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Telegram stores the menu server-side, so this only has to be sent
        // when it changes. Sending it every boot is simpler than tracking that,
        // and a failure only leaves a stale menu.
        api.setMyCommands(router.menuCommands());

        thread = new Thread(this::loop, "telegram-poller");
        thread.setDaemon(true);
        thread.start();
        log.info("telegram poller started");
    }

    private void loop() {
        long backoff = 1_000L;

        while (running.get()) {
            try {
                long offset = pollState.lastUpdateId() + 1;
                var updates = api.getUpdates(offset);
                backoff = 1_000L;

                for (var update : updates) {
                    if (!running.get()) {
                        break;
                    }
                    handle(update);
                    // The offset advances only after handling, so a crash
                    // mid-command replays that update rather than losing it.
                    // Every action is written to tolerate that: marking a
                    // deadline DONE twice, or deleting a deleted one, is a
                    // no-op rather than an error.
                    pollState.advanceTo(update.update_id());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (!running.get()) {
                    return;
                }
                log.warn("poll cycle failed, backing off {} ms", backoff, e);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }

    private void handle(TelegramDto.Update update) throws InterruptedException {
        if (update.callback_query() != null) {
            handleTap(update.callback_query());
            return;
        }

        var message = update.message();
        if (message == null || message.chat() == null || message.text() == null) {
            return;
        }

        long chatId = message.chat().id();
        if (!isOwner(chatId)) {
            return;
        }

        Reply reply = router.handle(chatId, message.text());
        api.sendMessage(chatId, reply.text(), reply.keyboard());
    }

    /**
     * A button tap. The message the buttons hang under is rewritten in place,
     * which is what makes it feel like a button rather than like another
     * command: the chat does not grow a new copy of the list every tap.
     */
    private void handleTap(TelegramDto.CallbackQuery query) {
        var message = query.message();
        if (message == null || message.chat() == null) {
            return;
        }
        long chatId = message.chat().id();
        if (!isOwner(chatId)) {
            return;
        }

        var result = callbacks.handle(chatId, query.data());

        // Answer first and unconditionally. An unanswered tap leaves the button
        // spinning on the phone for about a minute, including when the action
        // itself failed.
        api.answerCallbackQuery(query.id(), result.toast());

        if (result.text() != null) {
            api.editMessageText(chatId, message.message_id(), result.text(), result.keyboard());
        }
    }

    private boolean isOwner(long chatId) {
        if (chatId == props.ownerChatId()) {
            return true;
        }
        // The bot's username is public. Anyone can find it and start a chat;
        // only the owner gets to change state.
        log.info("ignoring update from unauthorised chat {}", chatId);
        return false;
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
        }
        log.info("telegram poller stopping");
    }
}
