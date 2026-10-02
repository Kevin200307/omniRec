// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.web;

import io.omnirec.tracker.ServerEventEmitter.ServerIdentity;

import java.util.Optional;

/**
 * The browsing identity behind the request being handled on this thread, read
 * by {@link OmnirecIdentityFilter} from the cookies the browser SDK sets.
 *
 * Lets backend code track events for the visitor without passing their ids
 * around. Empty outside a request, and on a different thread: for {@code @Async}
 * work, capture {@link #current()} first and pass the identity explicitly.
 */
public final class OmnirecRequestIdentity {

    private static final ThreadLocal<ServerIdentity> CURRENT = new ThreadLocal<>();

    private OmnirecRequestIdentity() {
    }

    public static Optional<ServerIdentity> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    static void set(ServerIdentity identity) {
        CURRENT.set(identity);
    }

    static void clear() {
        CURRENT.remove();
    }

    /** Runs {@code work} with {@code identity} as the current identity, for tests and non-HTTP entry points. */
    public static void runAs(ServerIdentity identity, Runnable work) {
        ServerIdentity previous = CURRENT.get();
        CURRENT.set(identity);
        try {
            work.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
