// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage.api;

import io.omnirec.commerce.identity.IdentityLinkStore;
import io.omnirec.commerce.privacy.ErasureRegistry;
import io.omnirec.commerce.storage.EventStore;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * {@code DELETE /v1/customers/{customerId}}: the right to erasure.
 *
 * <ol>
 *   <li>Finds every device of the customer: identity links (database and the
 *       pipeline's link store) and the anonymous ids on the customer's events.</li>
 *   <li>Writes tombstones for the customer and those devices first, so events
 *       arriving during or after the deletion are dropped at ingestion and by
 *       the storage worker.</li>
 *   <li>Deletes the customer's events, the devices' anonymous events, and the
 *       identity links, in one transaction.</li>
 *   <li>Forgets the links in the pipeline's link store, so the devices are no
 *       longer resolved to the customer.</li>
 * </ol>
 *
 * Authenticated like the history API, with a tenant's secret key. Returns a
 * receipt; repeating the request is harmless and returns zero counts.
 * Copies already delivered to providers (Personalize, Google, your webhooks)
 * are outside omniRec and must be deleted there.
 */
@RestController
public class CustomerErasureController {

    private static final Logger log = LoggerFactory.getLogger(CustomerErasureController.class);

    /** What was done, for the caller's records. Contains no personal data beyond the id they sent. */
    public record ErasureReceipt(String receiptId, String tenantId, String customerId, Instant erasedAt,
                                 long eventsDeleted, long identityLinksDeleted, int devicesErased) {
    }

    private final EventStore store;
    private final ErasureRegistry erasures;
    private final IdentityLinkStore linkStore;
    private final Clock clock;

    public CustomerErasureController(EventStore store, ErasureRegistry erasures, IdentityLinkStore linkStore,
                                     Clock clock) {
        this.store = store;
        this.erasures = erasures;
        this.linkStore = linkStore;
        this.clock = clock;
    }

    @DeleteMapping(value = "/v1/customers/{customerId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ErasureReceipt erase(@PathVariable String customerId, HttpServletRequest request) {
        String tenantId = tenantOf(request);
        if (customerId == null || customerId.isBlank() || customerId.length() > CustomerHistoryController.MAX_CUSTOMER_ID_LENGTH
                || customerId.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "customerId must be 1-"
                    + CustomerHistoryController.MAX_CUSTOMER_ID_LENGTH + " printable characters");
        }

        Set<String> devices = new LinkedHashSet<>(store.anonymousIdsOf(tenantId, customerId));
        if (linkStore != null) devices.addAll(linkStore.anonymousIdsFor(tenantId, customerId));

        erasures.record(tenantId, customerId, devices);
        EventStore.CustomerErasure erasure = store.eraseCustomer(tenantId, customerId, devices);
        if (linkStore != null) linkStore.forget(tenantId, customerId, devices);

        ErasureReceipt receipt = new ErasureReceipt("del_" + UUID.randomUUID(), tenantId, customerId, clock.instant(),
                erasure.eventsDeleted(), erasure.identityLinksDeleted(), devices.size());
        // The receipt id and counts, never the customer id: the log should not become a list of who left.
        log.info("Customer erasure {} for tenant {} by {}: {} event(s), {} link(s), {} device(s)",
                receipt.receiptId(), tenantId, request.getAttribute(CustomerHistoryAuthFilter.PRINCIPAL_ATTRIBUTE),
                receipt.eventsDeleted(), receipt.identityLinksDeleted(), receipt.devicesErased());
        return receipt;
    }

    private static String tenantOf(HttpServletRequest request) {
        Object tenant = request.getAttribute(CustomerHistoryAuthFilter.TENANT_ATTRIBUTE);
        if (tenant instanceof String tenantId && !tenantId.isBlank()) return tenantId;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid or missing secret key");
    }
}
