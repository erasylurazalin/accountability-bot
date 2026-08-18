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
 * the bot dials out and nothing has to be exposed inbound — which is what makes
 * hosting behind the flat's double NAT possible at all (NOTES.md section 2).
 */
@Component
public class TelegramPoller implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TelegramPoller.class);
    private static final long MAX_BACKOFF_MS = 60_000L;

    private final TelegramApi api;
    private final CommandRouter router;
    private final PollStateRepository pollState;
    private final BotProperties props;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread thread;

    public TelegramPoller(TelegramApi api, CommandRouter router,
                          PollStateRepository pollState, BotProperties props) {
        this.api = api;
        this.router = router;
        this.pollState = pollState;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
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
                    // Commands are written to tolerate that: /done and /skip
                    // only act on a PENDING check-in, so a replay is a no-op.
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
        var message = update.message();
        if (message == null || message.chat() == null || message.text() == null) {
            return;
        }

        long chatId = message.chat().id();
        if (chatId != props.ownerChatId()) {
            // The bot's username is public. Anyone can find it and start a chat;
            // only the owner gets to change state.
            log.info("ignoring message from unauthorised chat {}", chatId);
            return;
        }

        String reply = router.handle(chatId, message.text());
        api.sendMessage(chatId, reply);
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
