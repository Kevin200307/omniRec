// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.outbox;

import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.tracker.EventSender;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;

/**
 * Inside a database transaction, writes the event to the outbox table as part
 * of that transaction; the relay sends it after commit. A rolled-back order
 * therefore never produces an {@code order_placed}, and a committed one always
 * does, even if the collector is down at the moment of commit or the process
 * dies right after it.
 *
 * Outside a transaction, sends directly through the normal sender.
 */
public class OutboxEventSender implements EventSender {

    private final EventSender direct;
    private final OutboxStore store;
    private final OutboxRelay relay;
    private final Clock clock;

    public OutboxEventSender(EventSender direct, OutboxStore store, OutboxRelay relay, Clock clock) {
        this.direct = direct;
        this.store = store;
        this.relay = relay;
        this.clock = clock;
    }

    @Override
    public void send(CommerceEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            direct.send(event);
            return;
        }
        store.insert(event, clock.instant());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    relay.trigger();
                }
            });
        }
    }

    @Override
    public void flush() {
        direct.flush();
        relay.runOnce();
    }
}
