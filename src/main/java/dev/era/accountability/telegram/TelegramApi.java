package dev.era.accountability.telegram;

import dev.era.accountability.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class TelegramApi {

    private static final Logger log = LoggerFactory.getLogger(TelegramApi.class);

    private final RestClient client;
    private final BotProperties props;

    public TelegramApi(RestClient telegramRestClient, BotProperties props) {
        this.client = telegramRestClient;
        this.props = props;
    }

    /** Blocks up to {@code pollTimeout} seconds waiting for updates after {@code offset}. */
    public List<TelegramDto.Update> getUpdates(long offset) {
        var response = client.get()
                .uri(uri -> uri.path("/getUpdates")
                        .queryParam("offset", offset)
                        .queryParam("timeout", props.pollTimeout())
                        // Button taps arrive as callback_query. Without naming
                        // it here Telegram simply never sends them, and every
                        // button looks dead.
                        .queryParam("allowed_updates", "[\"message\",\"callback_query\"]")
                        .build())
                .retrieve()
                .body(TelegramDto.UpdatesResponse.class);

        if (response == null || !response.ok()) {
            throw new IllegalStateException("getUpdates failed: "
                    + (response == null ? "null body" : response.description()));
        }
        return response.result() == null ? List.of() : response.result();
    }

    /** @return the Telegram message id, or empty if the send was rejected. */
    public Optional<Long> sendMessage(long chatId, String text) {
        return sendMessage(chatId, text, null);
    }

    public Optional<Long> sendMessage(long chatId, String text, Object replyMarkup) {
        var body = new HashMap<String, Object>();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("parse_mode", "HTML");
        // Telegram turns a bare URL into a link preview card, which for a
        // reminder is a large grey box saying nothing.
        body.put("link_preview_options", Map.of("is_disabled", true));
        if (replyMarkup != null) {
            body.put("reply_markup", replyMarkup);
        }

        var response = client.post()
                .uri("/sendMessage")
                .body(body)
                .retrieve()
                .body(TelegramDto.SendMessageResponse.class);

        if (response == null || !response.ok() || response.result() == null) {
            log.warn("sendMessage to {} rejected: {}", chatId,
                    response == null ? "null body" : response.description());
            return Optional.empty();
        }
        return Optional.ofNullable(response.result().message_id());
    }

    /**
     * Rewrites a message that is already on screen, which is what makes a tap
     * feel like a button rather than like sending another command: the list you
     * tapped updates in place instead of the chat growing a new copy.
     */
    public void editMessageText(long chatId, long messageId, String text, Object replyMarkup) {
        var body = new HashMap<String, Object>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", text);
        body.put("parse_mode", "HTML");
        body.put("link_preview_options", Map.of("is_disabled", true));
        // An explicitly empty keyboard is how you remove buttons; omitting the
        // field leaves the old ones in place.
        body.put("reply_markup", replyMarkup == null ? Map.of("inline_keyboard", List.of()) : replyMarkup);

        try {
            client.post().uri("/editMessageText").body(body).retrieve()
                    .body(TelegramDto.SendMessageResponse.class);
        } catch (RuntimeException e) {
            // Telegram rejects an edit that would not change anything. Harmless.
            log.debug("editMessageText rejected", e);
        }
    }

    /**
     * Every tap must be answered or the button spins on the user's screen for
     * about a minute. The optional text appears as a small toast.
     */
    public void answerCallbackQuery(String callbackQueryId, String toast) {
        var body = new HashMap<String, Object>();
        body.put("callback_query_id", callbackQueryId);
        if (toast != null) {
            body.put("text", toast);
        }
        try {
            client.post().uri("/answerCallbackQuery").body(body).retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.debug("answerCallbackQuery failed", e);
        }
    }

    /**
     * Fills the blue Menu button next to the text box. Set once at startup:
     * Telegram stores it, so it survives restarts and is not per-message.
     */
    public void setMyCommands(List<Map<String, String>> commands) {
        try {
            client.post().uri("/setMyCommands")
                    .body(Map.of("commands", commands))
                    .retrieve().toBodilessEntity();
            log.info("registered {} commands in the Telegram menu", commands.size());
        } catch (RuntimeException e) {
            log.warn("setMyCommands failed, the menu will be stale", e);
        }
    }

    /** HTML parse_mode is on, so anything the user named must be escaped. */
    public static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
