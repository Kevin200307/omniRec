// SPDX-License-Identifier: Apache-2.0
package io.omnirec.derived.rules;

import io.omnirec.commerce.catalog.generated.StandardEvents;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventData;
import io.omnirec.derived.DerivedEventRule;
import io.omnirec.derived.DerivedEvents;
import io.omnirec.derived.RuleContext;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code new_customer_purchase} for a customer's first order and
 * {@code repeat_purchase} for every later one, from a per-customer counter.
 *
 * The customer is the user id when known, else the anonymous id. Orders are
 * counted once by order id, so a purchase sent from both browser and server,
 * or redelivered, is not counted twice. The counter starts when the rule is
 * enabled: orders placed before that are unknown to it, so an existing
 * customer's first order afterwards is reported as new.
 */
public class PurchaseHistoryRule implements DerivedEventRule {

    static final Duration ORDER_MEMORY = Duration.ofDays(400);

    @Override
    public String name() {
        return "purchase_history";
    }

    @Override
    public Set<String> subscribesTo() {
        return Set.of(StandardEvents.PURCHASE_COMPLETED);
    }

    @Override
    public void onEvent(CommerceEvent event, RuleContext context) {
        EventData.OrderData order = event.data() == null ? null : event.data().order();
        if (order == null || order.id() == null) return;
        String customer = Optional.ofNullable(event.identity().userId()).orElse(event.identity().anonymousId());
        String orderKey = event.tenantId() + ":" + order.id();

        // Count first, remember the result, then emit: a redelivery after a
        // crash re-emits with the same number and the same id, which
        // destinations drop as a duplicate.
        long number;
        if (context.firstTime("seen:" + orderKey, ORDER_MEMORY)) {
            number = context.increment("orders:" + event.tenantId() + ":" + customer);
            context.put("number:" + orderKey, String.valueOf(number), ORDER_MEMORY);
        } else {
            Optional<String> stored = context.get("number:" + orderKey);
            if (stored.isEmpty()) return;
            number = Long.parseLong(stored.get());
        }

        String name = number == 1 ? StandardEvents.NEW_CUSTOMER_PURCHASE : StandardEvents.REPEAT_PURCHASE;
        Map<String, Object> orderBlock = new LinkedHashMap<>();
        orderBlock.put("id", order.id());
        if (order.total() != null) orderBlock.put("total", order.total());
        if (order.currency() != null) orderBlock.put("currency", order.currency());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("purchaseNumber", number);

        context.emit(DerivedEvents.create(name, event.tenantId(), order.id(), event.identity(), event.timestamp(),
                context.now(), Map.of("order", orderBlock), properties));
    }
}
