// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.test;

import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.tracker.EventSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Captures tracked events in memory instead of sending them, for your
 * application's own tests. Enable it with {@link AutoConfigureOmnirecTest}:
 *
 * <pre>{@code
 * @SpringBootTest
 * @AutoConfigureOmnirecTest
 * class CheckoutTest {
 *     @Autowired OmnirecTestRecorder omnirec;
 *
 *     @Test
 *     void placingAnOrderTracksThePurchase() {
 *         checkout.placeOrder(cart);
 *         omnirec.assertThat("purchase_completed").withData("order.id", "o1").withUserId("c_1");
 *     }
 * }
 * }</pre>
 */
public class OmnirecTestRecorder implements EventSender {

    private final List<CommerceEvent> events = new ArrayList<>();

    @Override
    public synchronized void send(CommerceEvent event) {
        events.add(event);
    }

    /** Everything tracked so far, in order. */
    public synchronized List<CommerceEvent> events() {
        return List.copyOf(events);
    }

    /** Events with this name, in order. */
    public synchronized List<CommerceEvent> events(String name) {
        return events.stream().filter(e -> e.eventType().is(name)).toList();
    }

    public synchronized void clear() {
        events.clear();
    }

    /** Asserts exactly one event with this name was tracked and returns it for further checks. */
    public EventAssert assertThat(String name) {
        List<CommerceEvent> matching = events(name);
        if (matching.size() != 1) {
            throw new AssertionError("expected exactly one \"" + name + "\" event but found " + matching.size()
                    + "; tracked: " + events().stream().map(e -> e.eventType().wireName()).toList());
        }
        return new EventAssert(matching.get(0));
    }

    public void assertNotTracked(String name) {
        if (!events(name).isEmpty()) {
            throw new AssertionError("expected no \"" + name + "\" event but found " + events(name).size());
        }
    }

    /** Checks on one tracked event. */
    public static final class EventAssert {
        private final CommerceEvent event;

        EventAssert(CommerceEvent event) {
            this.event = event;
        }

        /** Checks a value in {@code data} by dotted path, for example {@code order.id}. */
        public EventAssert withData(String path, Object expected) {
            Object actual = event.data().get(path);
            if (!matches(expected, actual)) {
                throw new AssertionError("data." + path + ": expected " + expected + " but was " + actual);
            }
            return this;
        }

        public EventAssert withUserId(String expected) {
            if (!Objects.equals(expected, event.identity().userId())) {
                throw new AssertionError("userId: expected " + expected + " but was " + event.identity().userId());
            }
            return this;
        }

        public EventAssert withAnonymousId(String expected) {
            if (!Objects.equals(expected, event.identity().anonymousId())) {
                throw new AssertionError("anonymousId: expected " + expected + " but was " + event.identity().anonymousId());
            }
            return this;
        }

        public EventAssert withEventId(String expected) {
            if (!Objects.equals(expected, event.eventId())) {
                throw new AssertionError("eventId: expected " + expected + " but was " + event.eventId());
            }
            return this;
        }

        public CommerceEvent event() {
            return event;
        }

        private static boolean matches(Object expected, Object actual) {
            if (expected instanceof Number e && actual instanceof Number a) {
                return new java.math.BigDecimal(e.toString()).compareTo(new java.math.BigDecimal(a.toString())) == 0;
            }
            return Objects.equals(expected, actual);
        }
    }
}
