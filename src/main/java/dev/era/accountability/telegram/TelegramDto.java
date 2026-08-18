package dev.era.accountability.telegram;

import java.util.List;

/** Minimal slice of the Telegram Bot API payloads we actually read. */
public final class TelegramDto {
    private TelegramDto() {}

    public record ApiResponse<T>(boolean ok, T result, String description) {}

    public record Update(Long update_id, Message message) {}

    public record Message(Long message_id, Chat chat, String text, User from) {}

    public record Chat(Long id) {}

    public record User(Long id, String username) {}

    public record UpdatesResponse(boolean ok, List<Update> result, String description) {}

    public record SendMessageResponse(boolean ok, Message result, String description) {}
}
