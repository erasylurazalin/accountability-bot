package dev.era.accountability.telegram;

/**
 * One thing to do on screen, whether it came from a typed command or a tap.
 *
 * A button tap usually rewrites the message it came from, which is what makes
 * the bot feel like a small app: the list you tapped updates in place instead
 * of the chat growing a new copy. A typed command has no message to rewrite, so
 * it always sends.
 *
 * The toast is separate because Telegram demands an answer to every tap, even
 * one that only changed the message. It is also how a no-op says so: tapping
 * Done twice updates nothing and just says "Already done".
 *
 * `messageId` is only set when a typed answer has to rewrite the question that
 * asked for it. A tap already knows its own message; a text reply does not.
 */
public record UiAction(Mode mode, String text, Object markup, String toast, boolean alert,
                       Long messageId) {

    public enum Mode {
        /** Send a new message and leave the old one alone. */
        SEND,
        /** Rewrite a message: the one tapped, or the one in {@code messageId}. */
        REPLACE,
        /** Change nothing on screen; the toast carries the whole answer. */
        NOTHING
    }

    public static UiAction send(String text, Object markup) {
        return new UiAction(Mode.SEND, text, markup, null, false, null);
    }

    public static UiAction send(String text) {
        return send(text, null);
    }

    public static UiAction replace(String text, Object markup) {
        return new UiAction(Mode.REPLACE, text, markup, null, false, null);
    }

    /** Rewrites a specific message, used when the answer arrived as text. */
    public static UiAction replaceMessage(long messageId, String text, Object markup) {
        return new UiAction(Mode.REPLACE, text, markup, null, false, messageId);
    }

    public static UiAction toast(String toast) {
        return new UiAction(Mode.NOTHING, null, null, toast, false, null);
    }

    public static UiAction alert(String message) {
        return new UiAction(Mode.NOTHING, null, null, message, true, null);
    }

    /**
     * The same screen, delivered as a rewrite of the current message. Lets one
     * renderer serve both a typed command and a tap.
     */
    public UiAction inMode(Mode other) {
        return new UiAction(other, text, markup, toast, alert, messageId);
    }

    public UiAction withToast(String newToast) {
        return new UiAction(mode, text, markup, newToast, alert, messageId);
    }
}
