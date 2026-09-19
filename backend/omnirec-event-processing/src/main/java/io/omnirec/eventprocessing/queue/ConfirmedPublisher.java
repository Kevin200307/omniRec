package io.omnirec.eventprocessing.queue;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes a message and <em>waits for the broker to confirm it</em>.
 *
 * A plain {@code convertAndSend} returns as soon as the bytes are written to the
 * socket. If the broker then nacks the message (out of disk, queue limit) or
 * cannot route it, the publisher never finds out, and the caller has already
 * answered 202 or acked the message it was moving. The event is gone. Waiting
 * for the confirm is what turns "we sent it" into "the broker has it, durably".
 *
 * Every move in the pipeline goes through here: ingestion to the main queue,
 * and the consumer's moves to a retry tier or the dead-letter queue. The
 * consumer only acks the original after the move is confirmed, so a failed
 * move leaves the original in place to be redelivered rather than lost.
 *
 * Requires {@code spring.rabbitmq.publisher-confirm-type=correlated} and
 * {@code publisher-returns=true}; the auto-configuration refuses to start
 * without them rather than silently publishing unconfirmed.
 */
public class ConfirmedPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    public ConfirmedPublisher(RabbitTemplate rabbitTemplate, Duration confirmTimeout) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = confirmTimeout;
    }

    /**
     * @throws AmqpException if the broker nacks, returns the message as
     *         unroutable, or doesn't confirm within the timeout
     */
    public void publish(String exchange, String routingKey, Object payload, MessagePostProcessor postProcessor) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.convertAndSend(exchange, routingKey, payload, postProcessor, correlation);

        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmqpException("Interrupted waiting for publisher confirm", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AmqpException("No publisher confirm from the broker within " + confirmTimeout, e);
        }

        if (!confirm.isAck()) {
            throw new AmqpException("Broker refused the message: " + confirm.getReason());
        }
        // A mandatory message with no matching binding is acked *and* returned;
        // the return is what tells us it went nowhere.
        if (correlation.getReturned() != null) {
            throw new AmqpException("Message was unroutable: exchange=" + exchange + " routingKey=" + routingKey
                    + " (" + correlation.getReturned().getReplyText() + ")");
        }
    }
}
