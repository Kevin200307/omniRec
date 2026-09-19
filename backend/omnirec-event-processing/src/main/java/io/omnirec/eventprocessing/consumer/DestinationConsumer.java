package io.omnirec.eventprocessing.consumer;

import io.omnirec.commerce.destination.DestinationException;
import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.metrics.EventMetrics;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.eventprocessing.dispatch.EventDispatcher;
import io.omnirec.eventprocessing.queue.ConfirmedPublisher;
import io.omnirec.eventprocessing.queue.QueueTopology;
import io.omnirec.eventprocessing.queue.RetrySchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;

/**
 * One consumer per destination, bound to that destination's durable queue.
 *
 * <ol>
 *   <li><b>Transient failure</b>, attempt n: move the message to retry tier n,
 *       a queue with no consumer whose TTL is the delay for attempt n. Expiry
 *       dead-letters it back onto the main queue. The consumer thread is
 *       released immediately, so a provider outage backs up in the broker
 *       instead of stalling every worker.</li>
 *   <li><b>Retries exhausted, or a permanent failure</b>: move it to the
 *       destination's dead-letter queue with the reason attached.</li>
 * </ol>
 *
 * Every move is publisher-confirmed <em>before</em> this method returns, and
 * only then does the container ack the original. If a move fails, the
 * exception propagates, the container nacks, and the broker redelivers the
 * original: the event is delayed, never lost.
 */
public class DestinationConsumer {

    private static final Logger log = LoggerFactory.getLogger(DestinationConsumer.class);

    private final EventDestination destination;
    private final EventDispatcher dispatcher;
    private final ConfirmedPublisher publisher;
    private final EventMetrics metrics;
    private final RetrySchedule retrySchedule;

    public DestinationConsumer(
            EventDestination destination,
            EventDispatcher dispatcher,
            ConfirmedPublisher publisher,
            EventMetrics metrics,
            RetrySchedule retrySchedule
    ) {
        this.destination = destination;
        this.dispatcher = dispatcher;
        this.publisher = publisher;
        this.metrics = metrics;
        this.retrySchedule = retrySchedule;
    }

    public void handle(CommerceEvent event, Message rawMessage) {
        int retryCount = retryCountOf(rawMessage);

        try {
            dispatcher.dispatch(event, destination);
        } catch (DestinationException e) {
            if (!e.isRetryable()) {
                deadLetter(event, "permanent: " + rootMessage(e));
            } else if (retryCount >= retrySchedule.maxRetries()) {
                deadLetter(event, "exhausted " + retrySchedule.maxRetries() + " retries: " + rootMessage(e));
            } else {
                scheduleRetry(event, retryCount + 1, e);
            }
        }
    }

    private void scheduleRetry(CommerceEvent event, int attempt, Exception cause) {
        log.warn("Retry {}/{} for event {} to {} in {}: {}", attempt, retrySchedule.maxRetries(),
                event.eventId(), destination.id(), retrySchedule.delayFor(attempt), rootMessage(cause));

        // Default exchange: the routing key is the queue name, so reaching a
        // retry tier needs no extra exchange or binding.
        publisher.publish("", QueueTopology.retryQueueName(destination.id(), attempt), event, message -> {
            message.getMessageProperties().getHeaders().put(QueueTopology.RETRY_COUNT_HEADER, attempt);
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            message.getMessageProperties().setMessageId(event.eventId());
            return message;
        });
        metrics.retryScheduled(destination.id(), attempt);
    }

    private void deadLetter(CommerceEvent event, String reason) {
        log.error("Dead-lettering event {} for destination {}: {}", event.eventId(), destination.id(), reason);

        publisher.publish(QueueTopology.DEAD_LETTER_EXCHANGE, QueueTopology.routingKey(destination.id()), event,
                message -> {
                    message.getMessageProperties().getHeaders().put(QueueTopology.FAILURE_REASON_HEADER, reason);
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    message.getMessageProperties().setMessageId(event.eventId());
                    return message;
                });
        metrics.deadLettered(destination.id(), reason.startsWith("permanent") ? "permanent" : "exhausted");
        metrics.eventsFailed(event.tenantId(), "dead-lettered", 1);
    }

    private static int retryCountOf(Message message) {
        Object header = message == null ? null
                : message.getMessageProperties().getHeaders().get(QueueTopology.RETRY_COUNT_HEADER);
        return header instanceof Number number ? number.intValue() : 0;
    }

    /** Message only — never the stack trace or payload, which may hold merchant data. */
    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    public EventDestination destination() {
        return destination;
    }
}
