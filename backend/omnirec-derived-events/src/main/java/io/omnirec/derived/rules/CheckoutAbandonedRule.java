// SPDX-License-Identifier: Apache-2.0
package io.omnirec.derived.rules;

import io.omnirec.commerce.catalog.generated.StandardEvents;

import java.time.Duration;
import java.util.Set;

/** {@code checkout_abandoned}: a checkout start with no completed checkout or purchase within the timeout (default 30 minutes). */
public class CheckoutAbandonedRule extends AbandonmentRule {

    public CheckoutAbandonedRule(Duration timeout) {
        super(StandardEvents.CHECKOUT_ABANDONED,
                Set.of(StandardEvents.CHECKOUT_STARTED),
                Set.of(StandardEvents.CHECKOUT_COMPLETED, StandardEvents.PURCHASE_COMPLETED),
                timeout);
    }
}
