// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.storage;

/**
 * A storage operation failed and nothing was persisted.
 *
 * {@code retryable} separates "the database is unreachable or overloaded" from
 * "the database rejected this row and always will" (say, a string the column
 * type cannot hold). The storage worker maps it onto the same retry-or-dead-letter
 * decision every destination makes.
 */
public class EventStoreException extends RuntimeException {

    private final boolean retryable;

    public EventStoreException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
