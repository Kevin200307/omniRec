// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage.jdbc;

import io.omnirec.commerce.storage.EventStoreException;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

/**
 * Decides whether a failed statement is worth retrying, from its SQLSTATE.
 *
 * Only a <em>data exception</em> (class 22 — a value the column cannot hold,
 * such as a NUL character inside JSONB text) or an <em>integrity violation</em>
 * (class 23) is permanent: the same row will be refused on every attempt, so it
 * goes straight to the dead-letter queue instead of occupying five retries.
 *
 * Everything else is treated as transient — connection loss, failover,
 * serialization conflicts, lock timeouts, and also an undefined table (42P01),
 * which means "migrations have not run yet" and is fixed by a deployment, not by
 * giving up on the event. This mirrors the dispatcher's rule for destinations:
 * an unclassified failure must not silently discard events.
 */
public final class SqlFailures {

    private SqlFailures() {
    }

    public static EventStoreException translate(String message, SQLException e) {
        return new EventStoreException(message + " (SQLSTATE " + e.getSQLState() + ")", e, isRetryable(e));
    }

    public static boolean isRetryable(SQLException e) {
        if (e instanceof SQLTransientException || e instanceof SQLRecoverableException) {
            return true;
        }
        String state = e.getSQLState();
        if (state == null || state.length() < 2) {
            return true;
        }
        String sqlClass = state.substring(0, 2);
        return !sqlClass.equals("22") && !sqlClass.equals("23");
    }
}
