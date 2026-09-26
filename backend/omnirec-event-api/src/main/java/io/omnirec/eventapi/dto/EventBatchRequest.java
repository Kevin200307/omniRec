// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Body of POST /v1/events and /v1/events/batch.
 *
 * Events are bound as raw JSON and converted one at a time by the ingestion
 * service. Binding straight to typed events would make one bad event — an
 * unknown eventType from a newer SDK, a string where a number belongs — fail
 * JSON binding for the whole request, and the SDK would then drop every event
 * in the batch.
 *
 * {@code tenantId} here is advisory only; the authoritative tenant comes from
 * the API key.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventBatchRequest(String tenantId, List<JsonNode> events) {

    public List<JsonNode> events() {
        return events == null ? List.of() : events;
    }
}
