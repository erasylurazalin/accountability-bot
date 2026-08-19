package dev.era.accountability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param token         Telegram bot token from @BotFather.
 * @param ownerChatId   Only this chat may issue commands. Everything else is
 *                      ignored: the bot's username is discoverable, so without
 *                      this any stranger could create commitments.
 * @param pollTimeout   Long-poll timeout in seconds passed to getUpdates.
 * @param apiBaseUrl    Overridable for tests against a stub server.
 */
@ConfigurationProperties(prefix = "bot")
public record BotProperties(
        String token,
        long ownerChatId,
        int pollTimeout,
        String apiBaseUrl
) {
    public BotProperties {
        if (pollTimeout <= 0) pollTimeout = 50;
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) apiBaseUrl = "https://api.telegram.org";
    }
}
