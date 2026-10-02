// SPDX-License-Identifier: Apache-2.0
package io.omnirec.derived.rules;

import io.omnirec.commerce.catalog.generated.StandardEvents;

import java.time.Duration;
import java.util.Set;

/** {@code cart_abandoned}: an add to cart with no checkout or purchase within the timeout (default 60 minutes). */
public class CartAbandonedRule extends AbandonmentRule {

    public CartAbandonedRule(Duration timeout) {
        super(StandardEvents.CART_ABANDONED,
                Set.of(StandardEvents.PRODUCT_ADDED_TO_CART),
                Set.of(StandardEvents.CHECKOUT_STARTED, StandardEvents.CHECKOUT_COMPLETED,
                        StandardEvents.PURCHASE_COMPLETED),
                timeout);
    }
}
