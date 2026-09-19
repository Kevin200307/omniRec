package io.omnirec.commerce.identity;

import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The identity stage of the pipeline. Two jobs, in this order:
 *
 * <ol>
 *   <li>An {@code identify} event records a link and is then consumed — it is a
 *       control event and never reaches a provider.</li>
 *   <li>Any other event arriving with no userId is enriched from an existing
 *       link, so behaviour that happens after login on a device the user has
 *       used before is attributed to them even if the merchant forgot to
 *       identify on that page.</li>
 * </ol>
 *
 * What it deliberately does <em>not</em> do is backfill history. Events already
 * queued or delivered keep the identity they were captured with; the link makes
 * them attributable at query time without rewriting anything.
 */
public class IdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(IdentityResolver.class);

    private final IdentityLinkStore linkStore;

    public IdentityResolver(IdentityLinkStore linkStore) {
        this.linkStore = linkStore;
    }

    /**
     * Records the anonymous -> user association carried by an event. Called for
     * {@code identify} and for any event that names both ids, since a
     * {@code user_logged_in} is just as good a linking signal.
     */
    public void recordLinkIfPresent(CommerceEvent event) {
        String anonymousId = event.identity().anonymousId();
        String userId = event.identity().userId();
        if (anonymousId == null || anonymousId.isBlank() || userId == null || userId.isBlank()) {
            return;
        }
        linkStore.link(IdentityLink.of(event.tenantId(), anonymousId, userId));
        if (log.isDebugEnabled()) {
            log.debug("Linked anonymousId to userId for tenant {} (eventId={})", event.tenantId(), event.eventId());
        }
    }

    /**
     * Fills in a userId from a known link when the event didn't carry one.
     * An event that already names a user is returned untouched — the client
     * knows who is logged in right now better than a historical link does.
     */
    public CommerceEvent resolve(CommerceEvent event) {
        if (event.identity().isAuthenticated()) {
            return event;
        }
        return linkStore.resolveUserId(event.tenantId(), event.identity().anonymousId())
                .map(userId -> event.withIdentity(event.identity().resolvedTo(userId)))
                .orElse(event);
    }

    /**
     * Full identity stage: link, then resolve.
     *
     * Returns {@code null} for a control event, meaning "absorbed, do not
     * forward". Callers must handle that — an {@code identify} carries no
     * behavioural signal and would be meaningless to a provider.
     */
    public CommerceEvent process(CommerceEvent event) {
        recordLinkIfPresent(event);
        if (event.eventType() == EventType.IDENTIFY) {
            return null;
        }
        return resolve(event);
    }
}
