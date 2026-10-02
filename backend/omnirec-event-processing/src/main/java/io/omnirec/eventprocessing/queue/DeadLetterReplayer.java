// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventprocessing.queue;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves dead-lettered events back to their destination's main queue, after
 * the cause (an outage, a bad credential, a mapping bug) has been fixed.
 *
 * Each message is republished and confirmed by the broker before it is acked
 * on the dead-letter queue, so a failure midway leaves it where it was; at
 * worst it is delivered twice, which destinations tolerate. The retry count is
 * reset, so a replayed event gets the full retry schedule again.
 */
public class DeadLetterReplayer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterReplayer.class);
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(10);
    public static final String REPLAYED_AT_HEADER = "x-omnirec-replayed-at";

    /** What a replay did, or with {@code dryRun}, would do. */
    public record ReplayResult(String destination, boolean dryRun, long waiting, long replayed) {
    }

    private final ConnectionFactory connectionFactory;
    private final List<String> destinationIds;

    public DeadLetterReplayer(ConnectionFactory connectionFactory, List<String> destinationIds) {
        this.connectionFactory = connectionFactory;
        this.destinationIds = List.copyOf(destinationIds);
    }

    /** Messages waiting in each destination's dead-letter queue. */
    public Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String id : destinationIds) counts.put(id, count(id));
        return counts;
    }

    public long count(String destinationId) {
        requireKnown(destinationId);
        return withChannel(channel -> channel.messageCount(QueueTopology.deadLetterQueueName(destinationId)));
    }

    /**
     * Replays up to {@code max} messages. With {@code dryRun}, only reports
     * how many are waiting.
     */
    public ReplayResult replay(String destinationId, int max, boolean dryRun) {
        requireKnown(destinationId);
        if (max < 1) throw new IllegalArgumentException("max must be at least 1");
        long waiting = count(destinationId);
        if (dryRun || waiting == 0) return new ReplayResult(destinationId, dryRun, waiting, 0);

        String deadLetters = QueueTopology.deadLetterQueueName(destinationId);
        String main = QueueTopology.queueName(destinationId);
        long replayed = withChannel(channel -> {
            channel.confirmSelect();
            long moved = 0;
            while (moved < max) {
                GetResponse response = channel.basicGet(deadLetters, false);
                if (response == null) break;
                long tag = response.getEnvelope().getDeliveryTag();
                try {
                    channel.basicPublish("", main, true, resetHeaders(response.getProps()), response.getBody());
                    channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT.toMillis());
                    channel.basicAck(tag, false);
                    moved++;
                } catch (Exception e) {
                    channel.basicNack(tag, false, true);
                    throw e;
                }
            }
            return moved;
        });
        log.info("Replayed {} dead-lettered event(s) to {} ({} were waiting)", replayed, destinationId, waiting);
        return new ReplayResult(destinationId, false, waiting, replayed);
    }

    public List<String> destinations() {
        return destinationIds;
    }

    private static AMQP.BasicProperties resetHeaders(AMQP.BasicProperties props) {
        Map<String, Object> headers = new HashMap<>(props.getHeaders() == null ? Map.of() : props.getHeaders());
        headers.remove(QueueTopology.RETRY_COUNT_HEADER);
        headers.remove(QueueTopology.FAILURE_REASON_HEADER);
        headers.remove("x-death");
        headers.remove("x-first-death-exchange");
        headers.remove("x-first-death-queue");
        headers.remove("x-first-death-reason");
        headers.put(REPLAYED_AT_HEADER, Instant.now().toString());
        return props.builder().headers(headers).expiration(null).build();
    }

    private void requireKnown(String destinationId) {
        if (!destinationIds.contains(destinationId)) {
            throw new IllegalArgumentException("unknown destination '" + destinationId + "'; known: " + destinationIds);
        }
    }

    private interface ChannelWork<T> {
        T run(Channel channel) throws Exception;
    }

    private <T> T withChannel(ChannelWork<T> work) {
        try (Connection connection = connectionFactory.createConnection();
             Channel channel = connection.createChannel(false)) {
            return work.run(channel);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("dead-letter operation failed: " + e.getMessage(), e);
        }
    }
}
