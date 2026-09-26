// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventprocessing.consumer;

import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.SmartLifecycle;

import java.util.List;

/**
 * Owns the per-destination listener containers and ties them to the
 * application lifecycle.
 *
 * Containers are started only once the context has fully refreshed — so a
 * consumer can never receive a message before every bean it depends on exists —
 * and stopped on shutdown, so in-flight deliveries finish (or are left unacked
 * for redelivery) instead of consumer threads leaking past context close.
 */
public class DestinationListeners implements SmartLifecycle {

    private final List<SimpleMessageListenerContainer> containers;
    private volatile boolean running;

    public DestinationListeners(List<SimpleMessageListenerContainer> containers) {
        this.containers = List.copyOf(containers);
    }

    @Override
    public void start() {
        containers.forEach(SimpleMessageListenerContainer::start);
        running = true;
    }

    @Override
    public void stop() {
        containers.forEach(SimpleMessageListenerContainer::stop);
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Start late and stop early: consumers depend on everything else being up. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    public List<SimpleMessageListenerContainer> containers() {
        return containers;
    }
}
