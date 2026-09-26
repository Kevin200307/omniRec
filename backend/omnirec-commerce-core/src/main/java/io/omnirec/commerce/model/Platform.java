// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Platform {
    WEB("web"),
    IOS("ios"),
    ANDROID("android"),
    SERVER("server"),
    UNKNOWN("unknown");

    private final String wireName;

    Platform(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** Unlike EventType, an unrecognised platform degrades to UNKNOWN — it affects nothing downstream. */
    @JsonCreator
    public static Platform fromWireName(String value) {
        if (value == null) return UNKNOWN;
        for (Platform platform : values()) {
            if (platform.wireName.equalsIgnoreCase(value)) return platform;
        }
        return UNKNOWN;
    }
}
