// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.storage;

import io.omnirec.commerce.model.CommerceEvent;

/**
 * Historical storage for canonical events. The storage worker and the customer
 * history API depend on this and nothing else; whether the rows end up in a
 * local PostgreSQL, Neon, or TimescaleDB is decided by configuration.
 *
 * It stores the canonical {@link CommerceEvent} itself and hands the same type
 * back. There is no second "stored event" model to drift away from the first.
 *
 * Implementations must:
 * <ul>
 *   <li>be <strong>idempotent</strong> per (tenant, eventId): RabbitMQ is
 *       at-least-once, so the same event will sometimes be saved twice, and
 *       the second save must be a no-op reported as {@link SaveOutcome#DUPLICATE};</li>
 *   <li>record, atomically with the event, the anonymousId -> userId link an
 *       event carries when it names both (an {@code identify}, a login, or any
 *       event resolved to a user) — so history can attribute a device's earlier
 *       anonymous events to the customer <em>without rewriting them</em>;</li>
 *   <li>scope every read to exactly one tenant. The caller has already decided
 *       which tenant the requester may read; an implementation must never widen it;</li>
 *   <li>throw {@link EventStoreException} on failure, saying whether a retry
 *       could succeed, rather than swallowing it.</li>
 * </ul>
 */
public interface EventStore {

    enum SaveOutcome {
        /** Written now. */
        STORED,
        /** Already present — a redelivery. Nothing was written. */
        DUPLICATE
    }

    /**
     * Persists one event, and the identity link it carries if any.
     *
     * @throws EventStoreException if nothing was persisted
     */
    SaveOutcome save(CommerceEvent event);

    /**
     * One page of a customer's journey, newest first: the events captured with
     * {@code userId = customerId}, merged with the anonymous events of every
     * device linked to that customer.
     *
     * @param tenantId   the tenant the caller is authorised for — never taken from the request
     * @param customerId the userId
     */
    CustomerEventPage findCustomerEvents(String tenantId, String customerId, EventQuery query);
}
