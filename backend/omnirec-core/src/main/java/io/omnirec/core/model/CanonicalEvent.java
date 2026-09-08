package io.omnirec.core.model;

import java.util.Map;

/**
 * The one event shape every plugin, every controller, and every provider
 * mapper agrees on. This is the seam described in the design doc — adding
 * a new provider means writing a mapper from this shape, never changing it.
 */
public record CanonicalEvent(
        String eventId,
        String tenantId,
        String userId,
        String anonymousId,
        String sessionId,
        EventType eventType,
        EventCategory category,
        Map<String, Object> payload,
        EventContext context
) {
}
