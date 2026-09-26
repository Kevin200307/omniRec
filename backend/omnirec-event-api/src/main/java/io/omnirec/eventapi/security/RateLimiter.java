// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.security;

import io.omnirec.eventapi.config.EventApiProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window rate limit, per tenant per client IP.
 *
 * Because the API key is publishable, anyone can copy it out of a storefront's
 * JavaScript and POST with it. That's tolerable for a write-only, tenant-scoped
 * key, but not unbounded — so volume is capped here. The limit is per (tenant,
 * IP) rather than per tenant, so one abusive client can't exhaust the budget for
 * a merchant's real shoppers.
 *
 * A fixed window admits up to 2x the limit across a boundary. A sliding window
 * or token bucket would be tighter, and if this ever moves to a shared Redis
 * counter it should become one. For a first line of defence against a single
 * misbehaving client the simpler thing is the right trade — it is per-instance
 * state, so behind N replicas the effective limit is N x the configured value.
 */
public class RateLimiter {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final int requestsPerWindow;
    private final Duration windowDuration;
    private final Clock clock;

    public RateLimiter(EventApiProperties.RateLimit config) {
        this(config, Clock.systemUTC());
    }

    public RateLimiter(EventApiProperties.RateLimit config, Clock clock) {
        this.enabled = config.isEnabled();
        this.requestsPerWindow = config.getRequestsPerWindow();
        this.windowDuration = config.getWindow();
        this.clock = clock;
    }

    public boolean tryAcquire(String tenantId, String clientIp) {
        if (!enabled) return true;

        String key = (tenantId == null ? "_" : tenantId) + "|" + (clientIp == null ? "_" : clientIp);
        Instant now = clock.instant();

        Window window = windows.compute(key, (k, existing) ->
                existing == null || existing.hasExpired(now)
                        ? new Window(now.plus(windowDuration))
                        : existing);

        return window.count.incrementAndGet() <= requestsPerWindow;
    }

    /** Drops windows that have expired. Called on a schedule so the map can't grow without bound. */
    public void evictExpired() {
        Instant now = clock.instant();
        windows.entrySet().removeIf(entry -> entry.getValue().hasExpired(now));
    }

    public int trackedWindows() {
        return windows.size();
    }

    private static final class Window {
        private final Instant expiresAt;
        private final AtomicInteger count = new AtomicInteger();

        Window(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }

        boolean hasExpired(Instant now) {
            return !expiresAt.isAfter(now);
        }
    }
}
