package dev.era.accountability.telegram;

import dev.era.accountability.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
                        .queryParam("allowed_updates", "message")
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
        var response = client.post()
                .uri("/sendMessage")
                .body(Map.of("chat_id", chatId, "text", text))
                .retrieve()
                .body(TelegramDto.SendMessageResponse.class);

        if (response == null || !response.ok() || response.result() == null) {
            log.warn("sendMessage to {} rejected: {}", chatId,
                    response == null ? "null body" : response.description());
            return Optional.empty();
        }
        return Optional.ofNullable(response.result().message_id());
    }
}
