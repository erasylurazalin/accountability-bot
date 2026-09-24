package dev.era.accountability.telegram;

/**
 * What a command produces: some text, and optionally buttons to hang under it.
 *
 * The keyboard is a plain nested Map/List because that is exactly what the Bot
 * API wants as JSON, and a typed builder for a structure this small would be
 * more code than it saves.
 */
public record Reply(String text, Object keyboard) {

    public static Reply of(String text) {
        return new Reply(text, null);
    }

    public static Reply of(String text, Object keyboard) {
        return new Reply(text, keyboard);
    }
}
