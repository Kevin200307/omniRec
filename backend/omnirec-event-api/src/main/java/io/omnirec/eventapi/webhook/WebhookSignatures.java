// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** HMAC helpers shared by the webhook adapters and the outbound webhook destination. */
public final class WebhookSignatures {

    private WebhookSignatures() {
    }

    /** Lowercase hex HMAC-SHA256 of {@code payload}. */
    public static String hmacSha256Hex(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    /** Compares in time independent of where the strings differ, so a signature cannot be guessed byte by byte. */
    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The secret for a tenant: its own entry in {@code tenantSecrets}, else
     * {@code secret} when the tenant is the default one. Null when the tenant
     * has none, which must fail verification: a sender holding one tenant's
     * secret can never write to another tenant.
     */
    public static String secretFor(String tenantId, String defaultTenantId, String secret,
                                   Map<String, String> tenantSecrets) {
        String own = tenantSecrets == null ? null : tenantSecrets.get(tenantId);
        if (own != null && !own.isBlank()) return own;
        if (tenantId != null && tenantId.equals(defaultTenantId) && secret != null && !secret.isBlank()) return secret;
        return null;
    }
}
