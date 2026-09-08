package io.omnirec.web.dto;

import io.omnirec.core.model.EventCategory;
import io.omnirec.core.model.EventType;

import java.util.Map;

/** Mirrors @omnirec/core's OutgoingEvent (packages/core/src/types.ts). Keep in sync — see omnirec-contract-tests. */
public record EventDto(
        String eventId,
        String tenantId,
        String userId,
        String anonymousId,
        String sessionId,
        EventType eventType,
        EventCategory category,
        Map<String, Object> payload,
        ClientEventContextDto context,
        String capturedAt,
        String consent
) {
}
