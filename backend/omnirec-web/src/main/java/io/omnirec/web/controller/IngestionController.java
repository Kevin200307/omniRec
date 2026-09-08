package io.omnirec.web.controller;

import io.omnirec.core.model.CanonicalEvent;
import io.omnirec.core.model.EventCategory;
import io.omnirec.core.service.PersonalizationService;
import io.omnirec.web.dto.EventBatchRequest;
import io.omnirec.web.dto.EventDto;
import io.omnirec.web.enrichment.RequestContextEnricher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The single ingestion point every @omnirec/core Transport call hits,
 * regardless of which plugins are installed or which provider(s) the
 * backend has configured behind PersonalizationService.
 */
@RestController
public class IngestionController {

    private static final Logger log = LoggerFactory.getLogger(IngestionController.class);

    private final PersonalizationService personalizationService;
    private final RequestContextEnricher enricher;

    public IngestionController(PersonalizationService personalizationService, RequestContextEnricher enricher) {
        this.personalizationService = personalizationService;
        this.enricher = enricher;
    }

    @PostMapping("/v1/events")
    public ResponseEntity<Void> ingest(@RequestBody EventBatchRequest request, HttpServletRequest httpRequest) {
        int dropped = 0;
        for (EventDto dto : request.events()) {
            if (!isConsentSatisfied(dto)) {
                dropped++;
                continue;
            }
            CanonicalEvent event = new CanonicalEvent(
                    dto.eventId(),
                    dto.tenantId(),
                    dto.userId(),
                    dto.anonymousId(),
                    dto.sessionId(),
                    dto.eventType(),
                    dto.category(),
                    dto.payload(),
                    enricher.enrich(httpRequest, dto.context() != null ? dto.context().season() : null)
            );
            personalizationService.trackEvent(event);
        }
        if (dropped > 0) {
            log.debug("Dropped {} event(s) lacking consent for tenant {}", dropped, request.tenantId());
        }
        return ResponseEntity.accepted().build();
    }

    /** EXPLICIT events are first-party by construction — the developer called track() deliberately. IMPLICIT/CONTEXTUAL events require the client to have reported consent as granted. */
    private boolean isConsentSatisfied(EventDto dto) {
        return dto.category() == EventCategory.EXPLICIT || "granted".equals(dto.consent());
    }
}
