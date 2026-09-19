package io.omnirec.eventapi.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The standalone Event API service.
 *
 * This is the deployable that sits between storefronts and providers. It is a
 * separate process on purpose: provider credentials live here and only here, so
 * a merchant's storefront holds nothing but a publishable key, and the blast
 * radius of a compromised frontend is "someone can write events for one tenant".
 *
 * It bundles every destination starter. All of them are disabled by default, so
 * the service boots and the whole pipeline runs with no credentials at all —
 * enabling a provider is a config flag plus that provider's own server-side
 * credentials.
 */
@SpringBootApplication
@EnableScheduling
public class OmnirecEventApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(OmnirecEventApiApplication.class, args);
    }
}
