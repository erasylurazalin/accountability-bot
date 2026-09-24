package dev.era.accountability.telegram;

import java.util.List;

/** Minimal slice of the Telegram Bot API payloads we actually read. */
public final class TelegramDto {
    private TelegramDto() {}

    public record ApiResponse<T>(boolean ok, T result, String description) {}

    /**
     * A button tap arrives as a callback_query, not as a message, which is why
     * the poller has to ask for that update type explicitly. Telegram shows a
     * spinner on the button until answerCallbackQuery is called.
     */
    public record Update(Long update_id, Message message, CallbackQuery callback_query) {}

    public record CallbackQuery(String id, User from, Message message, String data) {}

    public record Message(Long message_id, Chat chat, String text, User from) {}

    public record Chat(Long id) {}

    public record User(Long id, String username) {}

    public record UpdatesResponse(boolean ok, List<Update> result, String description) {}

    public record SendMessageResponse(boolean ok, Message result, String description) {}
}
