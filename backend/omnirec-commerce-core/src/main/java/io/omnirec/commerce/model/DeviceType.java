// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum DeviceType {
    DESKTOP("desktop"),
    MOBILE("mobile"),
    TABLET("tablet"),
    UNKNOWN("unknown");

    private final String wireName;

    DeviceType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static DeviceType fromWireName(String value) {
        if (value == null) return UNKNOWN;
        for (DeviceType device : values()) {
            if (device.wireName.equalsIgnoreCase(value)) return device;
        }
        return UNKNOWN;
    }

    /** Coarse UA classification. Good enough for a segmentation signal; not a device database. */
    public static DeviceType fromUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return UNKNOWN;
        String ua = userAgent.toLowerCase();
        if (ua.contains("ipad") || ua.contains("tablet")) return TABLET;
        if (ua.contains("mobi") || ua.contains("iphone") || ua.contains("android")) return MOBILE;
        return DESKTOP;
    }
}
