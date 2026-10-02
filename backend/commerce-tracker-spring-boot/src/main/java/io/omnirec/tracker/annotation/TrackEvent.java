// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Tracks an event after the annotated method returns normally. Expressions are
 * SpEL, evaluated with the method's parameters by name and {@code #result}:
 *
 * <pre>{@code
 * @TrackEvent(value = StandardEvents.PURCHASE_COMPLETED,
 *             data = "{order: {id: #result.id, total: #result.total, currency: #result.currency, items: #result.lines}}",
 *             userId = "#result.customerId",
 *             businessKey = "#result.id")
 * public Order placeOrder(Cart cart) { ... }
 * }</pre>
 *
 * Inside a transaction with the outbox enabled, the event commits or rolls back
 * with the method's own work. A method that throws tracks nothing. A failure to
 * track is logged, never thrown: the business operation already succeeded.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TrackEvent {

    /** Event name: a catalog constant from {@code StandardEvents} or a plan event. */
    String value();

    /** SpEL for the event's data: a map, inline map ({@code {product: {id: #id}}}), or any object convertible to one. */
    String data() default "";

    /** SpEL for the customer id, when known. The browsing identity comes from the request. */
    String userId() default "";

    /** SpEL for a business key; derives a stable eventId so a retried call deduplicates. */
    String businessKey() default "";
}
