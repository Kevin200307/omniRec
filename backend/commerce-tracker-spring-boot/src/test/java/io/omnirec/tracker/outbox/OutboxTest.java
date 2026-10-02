// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.outbox;

import io.omnirec.commerce.catalog.generated.StandardEvents;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventData;
import io.omnirec.tracker.BatchDelivery;
import io.omnirec.tracker.EventSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The transactional outbox against a real PostgreSQL. */
@Testcontainers(disabledWithoutDocker = true)
class OutboxTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** A collector stand-in whose outcome the test controls. */
    static final class FakeCollector implements BatchDelivery {
        final List<CommerceEvent> delivered = Collections.synchronizedList(new ArrayList<>());
        volatile Outcome next = Outcome.DELIVERED;
        volatile long delayMs;

        @Override
        public Outcome deliverOnce(List<CommerceEvent> events) {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (next == Outcome.DELIVERED) delivered.addAll(events);
            return next;
        }
    }

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private DriverManagerDataSource dataSource;
    private TransactionTemplate appTransactions;
    private OutboxStore store;
    private FakeCollector collector;
    private MutableClock clock;
    private OutboxRelay relay;
    private OutboxEventSender sender;
    private final List<CommerceEvent> directlySent = new ArrayList<>();

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS omnirec_outbox");
        jdbc.execute("DROP TABLE IF EXISTS orders");
        jdbc.execute("CREATE TABLE orders (id TEXT PRIMARY KEY)");
        appTransactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        store = new OutboxStore(jdbc, "omnirec_outbox");
        store.createTableIfMissing();
        collector = new FakeCollector();
        clock = new MutableClock();
        relay = newRelay();
        EventSender direct = new EventSender() {
            @Override
            public void send(CommerceEvent event) {
                directlySent.add(event);
            }
        };
        sender = new OutboxEventSender(direct, store, relay, clock);
    }

    @AfterEach
    void tearDown() {
        relay.close();
    }

    private OutboxRelay newRelay() {
        return new OutboxRelay(store, collector, new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                10, Duration.ofSeconds(1), Duration.ofMinutes(1), clock);
    }

    private static CommerceEvent placed(String orderId) {
        return CommerceEvent.builder()
                .eventId("evt:purchase_completed:" + orderId)
                .eventType(StandardEvents.PURCHASE_COMPLETED)
                .data(EventData.of(Map.of("order", Map.of("id", orderId, "total", new BigDecimal("24.00")))))
                .build();
    }

    private void placeOrder(String orderId, boolean rollback) {
        appTransactions.executeWithoutResult(status -> {
            new JdbcTemplate(dataSource).update("INSERT INTO orders (id) VALUES (?)", orderId);
            sender.send(placed(orderId));
            if (rollback) status.setRollbackOnly();
        });
    }

    private void awaitDelivered(int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (collector.delivered.size() < count && System.currentTimeMillis() < deadline) Thread.sleep(20);
    }

    @Test
    void sendsAfterCommitAndEmptiesTheOutbox() throws InterruptedException {
        placeOrder("o1", false);
        awaitDelivered(1);
        assertEquals(List.of("evt:purchase_completed:o1"), collector.delivered.stream().map(CommerceEvent::eventId).toList());
        assertEquals(new BigDecimal("24.00"), collector.delivered.get(0).data().order().total(), "money survives the table");
        assertEquals(0, store.count());
    }

    @Test
    void sendsNothingWhenTheTransactionRollsBack() throws InterruptedException {
        placeOrder("o2", true);
        Thread.sleep(200);
        assertTrue(collector.delivered.isEmpty());
        assertEquals(0, store.count());
    }

    @Test
    void keepsTheEventThroughACollectorOutageAndDeliversLater() {
        collector.next = BatchDelivery.Outcome.RETRYABLE;
        placeOrder("o3", false);
        relay.runOnce();
        assertEquals(1, store.count(), "the event survives the failed attempt");

        collector.next = BatchDelivery.Outcome.DELIVERED;
        assertEquals(0, relay.runOnce(), "not due until the backoff has passed");
        clock.now = clock.now.plusSeconds(2);
        assertEquals(1, relay.runOnce());
        assertEquals(0, store.count());
    }

    @Test
    void dropsAPermanentlyRejectedEvent() {
        collector.next = BatchDelivery.Outcome.REJECTED;
        placeOrder("o4", false);
        relay.runOnce();
        assertEquals(0, store.count());
        assertTrue(collector.delivered.isEmpty());
    }

    @Test
    void storesADuplicateEventIdOnce() {
        collector.next = BatchDelivery.Outcome.RETRYABLE; // keep rows in the table
        appTransactions.executeWithoutResult(status -> {
            sender.send(placed("o5"));
            sender.send(placed("o5"));
        });
        assertEquals(1, store.count());
    }

    @Test
    void sendsDirectlyOutsideATransaction() {
        sender.send(placed("o6"));
        assertEquals(1, directlySent.size());
        assertEquals(0, store.count());
    }

    @Test
    void twoRelaysNeverSendTheSameRowTwice() throws Exception {
        // Insert directly: going through the sender would trigger a relay pass on commit.
        appTransactions.executeWithoutResult(status -> {
            for (int i = 0; i < 40; i++) store.insert(placed("bulk_" + i), clock.instant());
        });
        collector.delayMs = 30;

        OutboxRelay second = newRelay();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        pool.submit(() -> { start.await(); return relay.runOnce(); });
        pool.submit(() -> { start.await(); return second.runOnce(); });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        second.close();

        List<String> ids = collector.delivered.stream().map(CommerceEvent::eventId).toList();
        Set<String> unique = new HashSet<>(ids);
        assertEquals(ids.size(), unique.size(), "no event delivered twice");
        assertEquals(40, unique.size());
        assertEquals(0, store.count());
    }
}
