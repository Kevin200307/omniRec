package io.omnirec.eventprocessing.queue;

/**
 * Names for the RabbitMQ topology. Kept in one place so the publisher, the
 * consumers, and the declarations can't disagree about a string.
 *
 * <pre>
 *   omnirec.events (topic exchange)
 *        |  routing key: events.&lt;dest&gt;
 *        v
 *   omnirec.events.&lt;dest&gt;  ── consumer ──┬── delivered: ack
 *        |  (x-dead-letter-exchange       ├── transient failure, attempt n:
 *        |   = omnirec.events.dlx, for    │     publish to omnirec.events.&lt;dest&gt;.retry.n
 *        |   rejected/unparseable msgs)   │     (queue TTL = delay n, no consumer;
 *        v                                │      expiry dead-letters it back to the main queue)
 *   omnirec.events.dlx ───────────────────┴── exhausted or permanent: publish to the DLX
 *        v
 *   omnirec.events.&lt;dest&gt;.dlq
 * </pre>
 *
 * <b>One retry queue per attempt</b>, each with a single queue-level TTL.
 * RabbitMQ only expires messages at the head of a queue, so a single retry
 * queue holding mixed per-message TTLs would make a message due in 1s wait
 * behind one due in 5 minutes. Tiers keep every queue uniform.
 *
 * <b>One queue set per destination</b>, so Amazon being down backs up only
 * Amazon's queues while Google keeps draining, and adding a provider is a new
 * binding rather than a change to the publisher.
 *
 * Changing queue arguments is a migration: RabbitMQ refuses to redeclare an
 * existing queue with different arguments. See docs/rabbitmq.md.
 */
public final class QueueTopology {

    public static final String EXCHANGE = "omnirec.events";
    public static final String DEAD_LETTER_EXCHANGE = "omnirec.events.dlx";
    public static final String QUEUE_PREFIX = "omnirec.events.";
    public static final String DLQ_SUFFIX = ".dlq";
    public static final String RETRY_SUFFIX = ".retry";
    public static final String ROUTING_KEY_PREFIX = "events.";

    /** Header carrying how many times a message has been retried. */
    public static final String RETRY_COUNT_HEADER = "x-omnirec-retry-count";
    /** Header recording why a message was dead-lettered, for triage. */
    public static final String FAILURE_REASON_HEADER = "x-omnirec-failure-reason";

    private QueueTopology() {
    }

    public static String queueName(String destinationId) {
        return QUEUE_PREFIX + destinationId;
    }

    public static String deadLetterQueueName(String destinationId) {
        return QUEUE_PREFIX + destinationId + DLQ_SUFFIX;
    }

    /** Parking queue for retry {@code attempt} (1-based). Has no consumer by design. */
    public static String retryQueueName(String destinationId, int attempt) {
        return QUEUE_PREFIX + destinationId + RETRY_SUFFIX + "." + attempt;
    }

    public static String routingKey(String destinationId) {
        return ROUTING_KEY_PREFIX + destinationId;
    }
}
