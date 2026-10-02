// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.tenant;

import io.omnirec.commerce.validation.ValidationMode;
import io.omnirec.eventapi.config.EventApiProperties;
import io.omnirec.eventapi.security.EventApiRequestFilter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileTenantRegistryTest {

    private static EventApiProperties.Tenant tenant(String apiKey, String secretKey) {
        EventApiProperties.Tenant t = new EventApiProperties.Tenant();
        t.setApiKey(apiKey);
        t.setSecretKey(secretKey);
        return t;
    }

    @Test
    void resolvesKeysAndCarriesTenantSettings() {
        EventApiProperties properties = new EventApiProperties();
        EventApiProperties.Tenant a = tenant("pk_a", "sk_a");
        a.setAllowedOrigins(List.of("https://a.example"));
        a.setValidationMode(ValidationMode.STRICT);
        a.setPlanPaths(List.of("classpath:a.plan.yaml"));
        properties.getTenants().put("store-a", a);
        properties.getTenants().put("store-b", tenant("pk_b", null));

        FileTenantRegistry registry = new FileTenantRegistry(properties);

        assertEquals("store-a", registry.tenantForPublishableKey("pk_a").orElseThrow());
        assertEquals("store-b", registry.tenantForPublishableKey("pk_b").orElseThrow());
        assertEquals("store-a", registry.tenantForSecretKey("sk_a").orElseThrow());
        assertTrue(registry.tenantForPublishableKey("sk_a").isEmpty(), "a secret key is not a write key");
        assertTrue(registry.tenantForPublishableKey("pk_unknown").isEmpty());
        assertTrue(registry.tenantForPublishableKey(null).isEmpty());

        Tenant storeA = registry.find("store-a").orElseThrow();
        assertEquals(List.of("https://a.example"), storeA.allowedOrigins());
        assertEquals(ValidationMode.STRICT, storeA.validationMode());
        assertEquals(List.of("classpath:a.plan.yaml"), storeA.planPaths());
        assertEquals(ValidationMode.PERMISSIVE, registry.find("store-b").orElseThrow().validationMode(),
                "unset mode falls back to default-validation-mode");
        assertTrue(registry.hasAnyPublishableKey());
    }

    @Test
    void disabledTenantsKeepTheirSettingsButTheirKeysStopWorking() {
        EventApiProperties properties = new EventApiProperties();
        EventApiProperties.Tenant off = tenant("pk_off", "sk_off");
        off.setEnabled(false);
        properties.getTenants().put("old-store", off);

        FileTenantRegistry registry = new FileTenantRegistry(properties);

        assertTrue(registry.tenantForPublishableKey("pk_off").isEmpty());
        assertTrue(registry.tenantForSecretKey("sk_off").isEmpty());
        assertFalse(registry.find("old-store").orElseThrow().enabled());
        assertFalse(registry.hasAnyPublishableKey());
    }

    @Test
    void synthesisesTheDefaultTenantForOpenMode() {
        EventApiProperties properties = new EventApiProperties();
        properties.setDefaultPlanPaths(List.of("classpath:default.plan.yaml"));
        properties.setDefaultValidationMode(ValidationMode.STRICT);

        FileTenantRegistry registry = new FileTenantRegistry(properties);

        Tenant fallback = registry.find("default").orElseThrow();
        assertTrue(fallback.enabled());
        assertEquals(ValidationMode.STRICT, fallback.validationMode());
        assertEquals(List.of("classpath:default.plan.yaml"), fallback.planPaths());
        assertEquals(EventApiProperties.AuthMode.OPEN, EventApiRequestFilter.effectiveMode(properties, registry),
                "auto mode is open when no keys exist");
    }

    @Test
    void autoModeRequiresKeysOnceAnyTenantHasOne() {
        EventApiProperties properties = new EventApiProperties();
        properties.getTenants().put("store", tenant("pk_1", null));
        assertEquals(EventApiProperties.AuthMode.KEYS,
                EventApiRequestFilter.effectiveMode(properties, new FileTenantRegistry(properties)));

        properties.setAuthMode(EventApiProperties.AuthMode.OPEN);
        assertEquals(EventApiProperties.AuthMode.OPEN,
                EventApiRequestFilter.effectiveMode(properties, new FileTenantRegistry(properties)));
    }

    @Test
    @SuppressWarnings("deprecation")
    void theDeprecatedAnonymousFlagMeansOpenMode() {
        EventApiProperties properties = new EventApiProperties();
        properties.getTenants().put("store", tenant("pk_1", null));
        properties.setAllowAnonymousIngestion(true);
        assertEquals(EventApiProperties.AuthMode.OPEN,
                EventApiRequestFilter.effectiveMode(properties, new FileTenantRegistry(properties)));
    }

    @Test
    void refusesAKeyUsedByTwoTenants() {
        EventApiProperties properties = new EventApiProperties();
        properties.getTenants().put("a", tenant("pk_same", null));
        properties.getTenants().put("b", tenant("pk_same", null));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new FileTenantRegistry(properties));
        assertTrue(error.getMessage().contains("every key must be distinct"));
    }

    @Test
    void refusesASecretKeyThatIsAlsoAPublishableKey() {
        EventApiProperties properties = new EventApiProperties();
        properties.getTenants().put("a", tenant("pk_a", "pk_b"));
        properties.getTenants().put("b", tenant("pk_b", null));
        assertThrows(IllegalStateException.class, () -> new FileTenantRegistry(properties));
    }
}
