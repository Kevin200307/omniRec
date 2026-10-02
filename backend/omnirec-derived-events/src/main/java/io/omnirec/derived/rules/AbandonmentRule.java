// SPDX-License-Identifier: Apache-2.0
package io.omnirec.derived.rules;

import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventData;
import io.omnirec.derived.DerivedEventRule;
import io.omnirec.derived.DerivedEvents;
import io.omnirec.derived.RuleContext;
import io.omnirec.derived.Timer;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * "Started, then nothing followed in time." A start event (re)arms a timer per
 * visitor; an end event disarms it; a timer that fires emits the abandonment
 * event once.
 *
 * Visitors are keyed by anonymous id, which the browser and the server (via
 * the identity cookies) share for one shopper. Queues can deliver an end event
 * before the start it follows, so an end is remembered for a while and a start
 * older than it does not re-arm the timer.
 */
public abstract class AbandonmentRule implements DerivedEventRule {

    private final String emits;
    private final Set<String> starts;
    private final Set<String> ends;
    private final Duration timeout;

    protected AbandonmentRule(String emits, Set<String> starts, Set<String> ends, Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException(emits + " timeout must be positive");
        }
        this.emits = emits;
        this.starts = Set.copyOf(starts);
        this.ends = Set.copyOf(ends);
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return emits;
    }

    @Override
    public Set<String> subscribesTo() {
        Set<String> all = new HashSet<>(starts);
        all.addAll(ends);
        return all;
    }

    @Override
    public void onEvent(CommerceEvent event, RuleContext context) {
        String key = event.tenantId() + ":" + event.identity().anonymousId();
        String name = event.eventType().wireName();
        Instant at = event.timestamp();

        if (ends.contains(name)) {
            context.cancel(key);
            context.put("ended:" + key, String.valueOf(at.toEpochMilli()), timeout.multipliedBy(2));
            return;
        }
        if (!starts.contains(name)) return;

        String ended = context.get("ended:" + key).orElse(null);
        if (ended != null && Long.parseLong(ended) >= at.toEpochMilli()) return;

        Map<String, String> payload = DerivedEvents.identityPayload(event);
        payload.put("cause", event.eventId());
        payload.put("startedAt", at.toString());
        String cartId = cartId(event.data());
        if (cartId != null) payload.put("cartId", cartId);
        context.schedule(key, context.now().plus(timeout), payload);
    }

    @Override
    public void onTimer(Timer timer, RuleContext context) {
        String cartId = timer.get("cartId");
        Map<String, Object> cart = new LinkedHashMap<>();
        // cart.id is required; a start event without one is attributed to the visitor's cart.
        cart.put("id", cartId != null ? cartId : "visitor_" + timer.get("anonymousId"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("abandonedAfterMinutes", timeout.toMinutes());
        properties.put("lastActivityAt", timer.get("startedAt"));
        if (cartId == null) properties.put("cartIdInferred", true);

        context.emit(DerivedEvents.create(emits, timer.get("tenantId"), timer.get("cause"),
                DerivedEvents.identity(timer), timer.dueAt(), context.now(), Map.of("cart", cart), properties));
    }

    private static String cartId(EventData data) {
        if (data == null || data.cart() == null) return null;
        String id = data.cart().id();
        return id == null || id.isBlank() ? null : id;
    }
}
