package io.omnirec.commerce.destination;

/**
 * A delivery failure a destination considers worth retrying.
 *
 * {@code retryable} distinguishes "the provider was briefly unavailable" from
 * "the provider refused this payload and always will". The consumer uses it to
 * decide between requeueing and dead-lettering immediately — retrying a
 * permanently malformed event 5 times just delays the inevitable while holding
 * a consumer slot.
 */
public class DestinationException extends RuntimeException {

    private final String destinationId;
    private final boolean retryable;

    public DestinationException(String destinationId, String message, Throwable cause) {
        this(destinationId, message, cause, true);
    }

    public DestinationException(String destinationId, String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.destinationId = destinationId;
        this.retryable = retryable;
    }

    public static DestinationException permanent(String destinationId, String message) {
        return new DestinationException(destinationId, message, null, false);
    }

    public String getDestinationId() {
        return destinationId;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
