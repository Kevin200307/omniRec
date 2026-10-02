// SPDX-License-Identifier: Apache-2.0
package io.omnirec.derived.config;

import io.omnirec.derived.DerivedEventsEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fires due timers on a fixed delay, on its own thread, started and stopped
 * with the application. Every instance polls; the store hands each timer to
 * only one of them.
 */
public class DerivedEventsPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DerivedEventsPoller.class);

    private final DerivedEventsEngine engine;
    private final Duration interval;
    private ScheduledExecutorService executor;

    public DerivedEventsPoller(DerivedEventsEngine engine, Duration interval) {
        this.engine = engine;
        this.interval = interval;
    }

    @Override
    public synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "omnirec-derived-timers");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::poll, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void poll() {
        try {
            int fired = engine.fireDue();
            if (fired > 0) log.debug("Fired {} derived-event timer(s)", fired);
        } catch (RuntimeException e) {
            // The store is unreachable; the timers stay put and are tried next round.
            log.warn("Could not check derived-event timers: {}", e.getMessage());
        }
    }

    @Override
    public synchronized void stop() {
        if (executor == null) return;
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
