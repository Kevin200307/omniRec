// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventprocessing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "omnirec.processing")
public class EventProcessingProperties {

    /**
     * Route events through RabbitMQ. When false, destinations are called
     * inline — fine for local development, not for a deployment, because
     * there is then no retry and no dead-letter queue.
     */
    private boolean queueEnabled = true;

    /** Attempts after the first before a message is dead-lettered. */
    private int maxRetries = 5;

    /** Delay before the first retry; each later retry doubles it. */
    private Duration retryInitialInterval = Duration.ofSeconds(1);

    /** Ceiling on the retry delay, so a long outage doesn't schedule hour-long waits. */
    private Duration retryMaxInterval = Duration.ofMinutes(5);

    /** Consumer threads per destination queue. */
    private int concurrency = 2;

    /** How long a delivered eventId is remembered, per destination. */
    private Duration deduplicationWindow = Duration.ofHours(24);

    /**
     * How long a consumer holds a delivery lease. Must outlast the slowest
     * provider call; if a consumer crashes mid-send, the event is retried
     * after at most this long.
     */
    private Duration deliveryLease = Duration.ofMinutes(2);

    /** How long to wait for the broker to confirm a publish before failing it. */
    private Duration confirmTimeout = Duration.ofSeconds(10);

    /**
     * Unacked messages allowed per consumer. Low on purpose: a consumer that
     * dies with a large prefetch has more in-flight messages to redeliver, and
     * delivery is IO-bound, so there is nothing to gain from buffering deeply.
     */
    private int prefetchCount = 10;

    public boolean isQueueEnabled() { return queueEnabled; }
    public void setQueueEnabled(boolean queueEnabled) { this.queueEnabled = queueEnabled; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public Duration getRetryInitialInterval() { return retryInitialInterval; }
    public void setRetryInitialInterval(Duration retryInitialInterval) { this.retryInitialInterval = retryInitialInterval; }
    public Duration getRetryMaxInterval() { return retryMaxInterval; }
    public void setRetryMaxInterval(Duration retryMaxInterval) { this.retryMaxInterval = retryMaxInterval; }
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    public Duration getDeduplicationWindow() { return deduplicationWindow; }
    public void setDeduplicationWindow(Duration deduplicationWindow) { this.deduplicationWindow = deduplicationWindow; }
    public Duration getDeliveryLease() { return deliveryLease; }
    public void setDeliveryLease(Duration deliveryLease) { this.deliveryLease = deliveryLease; }
    public Duration getConfirmTimeout() { return confirmTimeout; }
    public void setConfirmTimeout(Duration confirmTimeout) { this.confirmTimeout = confirmTimeout; }
    public int getPrefetchCount() { return prefetchCount; }
    public void setPrefetchCount(int prefetchCount) { this.prefetchCount = prefetchCount; }
}
