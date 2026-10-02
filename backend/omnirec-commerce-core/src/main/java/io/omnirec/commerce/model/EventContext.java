// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Where an event happened.
 *
 * {@code ip} and {@code country} are server-derived and the Event API
 * overwrites whatever the client sent. Everything else is client-reported and
 * therefore advisory — a spoofed {@code url} is a data-quality problem, not a
 * security one, whereas a spoofed IP would be.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventContext(
        String url,
        String path,
        String referrer,
        Platform platform,
        DeviceType device,
        String userAgent,
        String locale,
        String timezone,
        Integer screenWidth,
        Integer screenHeight,
        String ip,
        String country,
        /** Marketing attribution parsed from the landing URL, persisted for the session. */
        Campaign campaign,
        /** Page kind the storefront declared, for example product or cart. */
        PageInfo page
) {

    public EventContext {
        platform = platform == null ? Platform.UNKNOWN : platform;
        device = device == null ? DeviceType.UNKNOWN : device;
    }

    /** The v1 shape, without campaign or page. */
    public EventContext(String url, String path, String referrer, Platform platform, DeviceType device,
                        String userAgent, String locale, String timezone, Integer screenWidth,
                        Integer screenHeight, String ip, String country) {
        this(url, path, referrer, platform, device, userAgent, locale, timezone, screenWidth, screenHeight,
                ip, country, null, null);
    }

    /** UTM parameters and ad click ids from the landing URL. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Campaign(String source, String medium, String name, String term, String content,
                           String clickId, String clickIdType) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PageInfo(String type, String title) {
    }

    public static EventContext empty() {
        return new EventContext(null, null, null, Platform.UNKNOWN, DeviceType.UNKNOWN,
                null, null, null, null, null, null, null);
    }

    public static EventContext server() {
        return new EventContext(null, null, null, Platform.SERVER, DeviceType.UNKNOWN,
                null, null, null, null, null, null, null);
    }

    /**
     * Replaces the request-derived fields with values the server established
     * itself, discarding any the client proposed.
     */
    public EventContext enrichedWith(String resolvedIp, String resolvedCountry, DeviceType resolvedDevice) {
        return new EventContext(url, path, referrer, platform,
                resolvedDevice == null || resolvedDevice == DeviceType.UNKNOWN ? device : resolvedDevice,
                userAgent, locale, timezone, screenWidth, screenHeight, resolvedIp, resolvedCountry, campaign, page);
    }

    /** Replaces the URL fields — used to scrub secrets and personal data from query strings. */
    public EventContext withUrls(String newUrl, String newReferrer) {
        return new EventContext(newUrl, path, newReferrer, platform, device, userAgent, locale,
                timezone, screenWidth, screenHeight, ip, country, campaign, page);
    }

    /** Drops the IP entirely — used when a tenant has opted out of IP retention. */
    public EventContext withoutIp() {
        return new EventContext(url, path, referrer, platform, device, userAgent, locale,
                timezone, screenWidth, screenHeight, null, country, campaign, page);
    }
}
