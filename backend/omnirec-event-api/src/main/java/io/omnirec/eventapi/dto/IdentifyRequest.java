package io.omnirec.eventapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Body of POST /v1/identify — a convenience for server-side code that wants to
 * link an anonymous visitor to a user without constructing a full event.
 *
 * It is a thin shim: internally it becomes an ordinary {@code identify} event
 * and travels the same pipeline as everything else. Keeping one code path for
 * identity means there is no second place for the rules to drift.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IdentifyRequest(
        String anonymousId,
        String userId,
        String sessionId,
        Map<String, Object> traits
) {
}
