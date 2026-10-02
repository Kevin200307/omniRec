// SPDX-License-Identifier: Apache-2.0
package io.omnirec.derived.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Derived events. Off by default; see docs/derived-events.md. */
@ConfigurationProperties(prefix = "omnirec.derived")
public class DerivedEventsProperties {

    private boolean enabled = false;

    /** Where timers and counters live. {@code auto}: Redis when omnirec-redis-state is enabled, else memory. */
    private Store store = Store.AUTO;

    /** Prefix of every Redis key this module writes. */
    private String redisKeyPrefix = "omnirec:derived";

    /** How often due timers are checked. An abandonment fires at most this late. */
    private Duration pollInterval = Duration.ofSeconds(15);

    private final Abandonment cartAbandoned = new Abandonment(Duration.ofMinutes(60));
    private final Abandonment checkoutAbandoned = new Abandonment(Duration.ofMinutes(30));
    private final ReturnVisit returnVisit = new ReturnVisit();
    private final Toggle purchaseHistory = new Toggle();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Store getStore() { return store; }
    public void setStore(Store store) { this.store = store; }
    public String getRedisKeyPrefix() { return redisKeyPrefix; }
    public void setRedisKeyPrefix(String redisKeyPrefix) { this.redisKeyPrefix = redisKeyPrefix; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    public Abandonment getCartAbandoned() { return cartAbandoned; }
    public Abandonment getCheckoutAbandoned() { return checkoutAbandoned; }
    public ReturnVisit getReturnVisit() { return returnVisit; }
    public Toggle getPurchaseHistory() { return purchaseHistory; }

    public enum Store { AUTO, MEMORY, REDIS }

    public static class Toggle {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Abandonment extends Toggle {
        private Duration timeout;

        Abandonment(Duration timeout) {
            this.timeout = timeout;
        }

        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }

    public static class ReturnVisit extends Toggle {
        /** A new session counts as a return when the previous one started at least this long before. */
        private Duration minimumGap = Duration.ofMinutes(30);

        public Duration getMinimumGap() { return minimumGap; }
        public void setMinimumGap(Duration minimumGap) { this.minimumGap = minimumGap; }
    }
}
