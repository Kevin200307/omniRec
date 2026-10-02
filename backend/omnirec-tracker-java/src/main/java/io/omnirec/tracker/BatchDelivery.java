// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker;

import io.omnirec.commerce.model.CommerceEvent;

import java.util.List;

/**
 * One delivery attempt, with its outcome reported rather than retried. Used by
 * callers that own their own retry policy, such as the transactional outbox
 * relay, which must know whether to delete a row or try it again later.
 */
public interface BatchDelivery {

    enum Outcome {
        /** Accepted by the collector. */
        DELIVERED,
        /** Transient: 5xx, 408, 429, network error. Try again later. */
        RETRYABLE,
        /** Permanent: the collector refused the payload or the key. Retrying cannot help. */
        REJECTED
    }

    Outcome deliverOnce(List<CommerceEvent> events);
}
