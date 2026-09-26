// SPDX-License-Identifier: Apache-2.0
package io.omnirec.core.model;

import java.time.Instant;

/**
 * Contextual signals — always stamped by {@code IngestionController}'s
 * enrichment step, never trusted from the client payload. See
 * omnirec-web's RequestContextEnricher.
 */
public record EventContext(
        String ip,
        String deviceType,
        String country,
        String city,
        Instant timestamp,
        String season
) {
    public static EventContext of(String ip, String deviceType, String country, String city, Instant timestamp) {
        return new EventContext(ip, deviceType, country, city, timestamp, null);
    }
}
