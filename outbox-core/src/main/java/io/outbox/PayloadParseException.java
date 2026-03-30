package io.outbox;

/**
 * Thrown when an event payload cannot be deserialized from JSON.
 *
 * <p>This is an {@link UnrecoverableException} because JSON parse errors are
 * deterministic — retrying the same malformed payload will always fail.
 */
public class PayloadParseException extends UnrecoverableException {

    public PayloadParseException(String message) {
        super(message);
    }

    public PayloadParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
