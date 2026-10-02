// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.controller;

import io.omnirec.commerce.model.EventData;
import io.omnirec.commerce.model.EventSource;
import io.omnirec.commerce.model.EventContext;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.model.EventName;
import io.omnirec.eventapi.config.EventApiProperties;
import io.omnirec.eventapi.dto.EventBatchRequest;
import io.omnirec.eventapi.dto.EventDto;
import io.omnirec.eventapi.dto.IdentifyRequest;
import io.omnirec.eventapi.dto.IngestResponse;
import io.omnirec.eventapi.ingest.EventIngestionService;
import io.omnirec.eventapi.normalize.EventNormalizer;
import io.omnirec.eventapi.security.EventApiRequestFilter;
import io.omnirec.eventapi.tenant.TenantCatalogs;
import io.omnirec.commerce.catalog.EventDefinition;
import io.omnirec.commerce.catalog.EventRegistry;
import io.omnirec.commerce.validation.ValidationMode;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.LinkedHashMap;
import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The entire public surface of the Event API: three endpoints, deliberately.
 *
 * <pre>
 *   POST /v1/events        one or more events
 *   POST /v1/events/batch  the same thing, named for what the SDK does
 *   POST /v1/identify      convenience shim for identity linking
 * </pre>
 *
 * There is no GET, no query API, no dashboard. This service collects and
 * forwards; reading personalisation results is a different service's job.
 *
 * Both event endpoints accept {@code text/plain} as well as JSON, because
 * {@code navigator.sendBeacon} — the only way to reliably flush events as a
 * page unloads — cannot set a Content-Type that would survive CORS preflight.
 */
@RestController
public class EventController {


    private final EventIngestionService ingestionService;
    private final EventApiProperties properties;
    private final TenantCatalogs catalogs;

    public EventController(EventIngestionService ingestionService, EventApiProperties properties) {
        this(ingestionService, properties, null);
    }

    public EventController(EventIngestionService ingestionService, EventApiProperties properties,
                           TenantCatalogs catalogs) {
        this.ingestionService = ingestionService;
        this.properties = properties;
        this.catalogs = catalogs;
    }

    /**
     * The events this tenant may send: the standard catalog plus its tracking
     * plan. Used by SDK debug tooling for did-you-mean hints and field checks.
     * Needs the same credentials as writing events; contains no event data.
     */
    @GetMapping(value = "/v1/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> catalog(HttpServletRequest httpRequest) {
        String tenantId = tenantOf(httpRequest);
        EventRegistry registry = catalogs == null ? EventRegistry.standard() : catalogs.forTenant(tenantId).registry();
        ValidationMode mode = catalogs == null ? ValidationMode.STRICT : catalogs.forTenant(tenantId).validator().mode();

        List<Map<String, Object>> events = new java.util.ArrayList<>();
        for (EventDefinition event : registry.events()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", event.name());
            entry.put("domain", event.domain());
            entry.put("version", event.version());
            entry.put("kind", event.kind());
            entry.put("sources", event.sources());
            entry.put("aliases", event.aliases());
            entry.put("required", event.required());
            entry.put("fields", event.fields());
            events.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("catalogVersion", registry.catalogVersion());
        body.put("validationMode", mode.name().toLowerCase(java.util.Locale.ROOT));
        body.put("vocabularies", registry.vocabularies());
        body.put("events", events);
        return body;
    }

    @PostMapping(
            value = {"/v1/events", "/v1/events/batch"},
            consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestResponse> ingest(
            @RequestBody EventBatchRequest request,
            HttpServletRequest httpRequest
    ) {
        String tenantId = tenantOf(httpRequest);

        List<JsonNode> events = request.events();
        if (events.isEmpty()) {
            return ResponseEntity.accepted().body(IngestResponse.of(0, 0, 0, List.of()));
        }
        if (events.size() > properties.getMaxBatchSize()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "batch exceeds maxBatchSize of " + properties.getMaxBatchSize());
        }

        return respond(ingestionService.ingestJson(events, tenantId, requestMetadata(httpRequest)));
    }

    /**
     * 202 normally. 503 with Retry-After when some events are held by an
     * in-progress lease: the SDK treats 503 as retryable and resends the batch,
     * and the events accepted this time come back as harmless duplicates.
     */
    private ResponseEntity<IngestResponse> respond(IngestResponse response) {
        if (response.needsRetry()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "5")
                    .body(response);
        }
        return ResponseEntity.accepted().body(response);
    }

    /**
     * Linking without constructing a whole event. Builds an {@code identify}
     * event and pushes it through the same pipeline — no second code path for
     * identity rules to drift along.
     */
    @PostMapping(value = "/v1/identify", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestResponse> identify(
            @RequestBody IdentifyRequest request,
            HttpServletRequest httpRequest
    ) {
        String tenantId = tenantOf(httpRequest);

        if (isBlank(request.anonymousId()) || isBlank(request.userId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "anonymousId and userId are both required");
        }

        EventDto dto = EventDto.v2(
                UUID.randomUUID().toString(),
                StandardEventNames.IDENTIFY,
                EventSource.SERVER,
                Instant.now(),
                EventIdentity.authenticated(
                        request.anonymousId(),
                        request.userId(),
                        // A server-side identify may legitimately have no browsing
                        // session; synthesise one so the event still validates.
                        isBlank(request.sessionId()) ? "server_" + UUID.randomUUID() : request.sessionId()),
                EventContext.server(),
                EventData.empty(),
                request.traits() == null ? Map.of() : Map.of("traits", request.traits())
        );

        return respond(ingestionService.ingest(List.of(dto), tenantId, requestMetadata(httpRequest)));
    }

    /**
     * Authentication, the payload cap and rate limiting all run earlier, in
     * EventApiRequestFilter, before the body is parsed. By the time a request
     * gets here its tenant is established. If the filter is somehow absent,
     * fail closed rather than accept an unauthenticated write.
     */
    private String tenantOf(HttpServletRequest request) {
        Object tenant = request.getAttribute(EventApiRequestFilter.TENANT_ATTRIBUTE);
        if (tenant instanceof String tenantId) {
            return tenantId;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid or missing API key");
    }

    private EventNormalizer.RequestMetadata requestMetadata(HttpServletRequest request) {
        return new EventNormalizer.RequestMetadata(
                clientIp(request),
                firstNonBlank(
                        request.getHeader("CloudFront-Viewer-Country"),
                        request.getHeader("CF-IPCountry"),
                        request.getHeader("X-Geo-Country")),
                request.getHeader("User-Agent"),
                Instant.now());
    }

    /**
     * The address of the connection, never a raw X-Forwarded-For header. That
     * header is written by the client, so keying the rate limit on it let anyone
     * bypass the limit by rotating a fake address. Behind a load balancer, set
     * server.forward-headers-strategy=native: Tomcat then resolves the real
     * client address, trusting forwarded headers only from internal proxies.
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
