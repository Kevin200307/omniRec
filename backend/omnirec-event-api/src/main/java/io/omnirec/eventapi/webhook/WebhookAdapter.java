// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.webhook;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Turns another system's webhook into omniRec events. Register an
 * implementation as a bean and it is served at {@code POST /v1/webhooks/{source}}.
 *
 * The collector calls {@link #verify} first and rejects the call with 401 when
 * it returns false, so an adapter never sees an unauthenticated body in
 * {@link #translate}. The events it returns are v2 envelopes (see
 * {@link WebhookEnvelope}); the collector forces {@code source: webhook} on each
 * and sends them through the normal pipeline, so they are normalized, validated
 * against the tenant's catalog and deduplicated like any other event. Give each
 * event a stable id derived from the sender's own id, so the sender's retries
 * are recognised as duplicates.
 */
public interface WebhookAdapter {

    /** The path segment this adapter serves, for example {@code stripe}. */
    String source();

    /**
     * Whether the call really comes from the sender and is meant for
     * {@code request.tenantId()}. Must use a constant-time comparison
     * (see {@link WebhookSignatures}).
     */
    boolean verify(WebhookRequest request);

    /**
     * The events this call describes. An empty list means the call is valid
     * but not interesting, for example a Stripe event type with no mapping;
     * the sender gets a 2xx and stops retrying.
     *
     * @throws WebhookPayloadException when the body cannot be understood
     */
    List<ObjectNode> translate(WebhookRequest request);
}
