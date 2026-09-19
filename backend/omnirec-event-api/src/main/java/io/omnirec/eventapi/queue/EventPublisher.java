package io.omnirec.eventapi.queue;

import io.omnirec.commerce.model.CommerceEvent;

/**
 * Hands a validated, identity-resolved event to the queue.
 *
 * An interface, not a direct AMQP call, for two reasons: the Event API shouldn't
 * depend on a broker client to be testable, and a single-node deployment that
 * doesn't want RabbitMQ can run the in-memory implementation and still get the
 * whole pipeline.
 *
 * Implementations must be synchronous enough to report failure: if publishing
 * fails, ingestion needs to know so the client can retry, rather than returning
 * 202 for an event that never reached the queue.
 */
public interface EventPublisher {

    void publish(CommerceEvent event);
}
