package io.omnirec.catalog.diagnostics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.List;

/**
 * Pre-flight check only — never attempts a real catalog sync. Registered
 * as a bean (see OmnirecWebAutoConfiguration) only under the
 * "feed-diagnose" Spring profile, so it never fires during normal
 * application startup — deliberately NOT a @Component, since component
 * scanning only reaches a developer's own package tree, not this library's;
 * everything here is wired via @AutoConfiguration instead, same as the
 * rest of Omnirec.
 *
 * Uses ApplicationRunner (not CommandLineRunner) specifically so the
 * provider-name argument can be told apart from Spring's own --key=value
 * options — CommandLineRunner.run(String... args) receives the raw,
 * unfiltered argument list, so "--server.port=8080" would otherwise be
 * mistaken for the provider name if it happened to come first.
 * ApplicationArguments.getNonOptionArgs() gives just the bare word(s):
 *
 *   java -jar app.jar --spring.profiles.active=feed-diagnose google-merchant
 *
 * With no provider name argument, every registered FeedDiagnostic runs.
 * Resolves FeedDiagnostic beans generically — never imports
 * GoogleMerchantDiagnostic or OpenAIFeedDiagnostic directly, same
 * no-direct-adapter-imports rule as everywhere else in Omnirec.
 */
public class FeedDiagnosticRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FeedDiagnosticRunner.class);

    private final List<FeedDiagnostic> diagnostics;

    public FeedDiagnosticRunner(List<FeedDiagnostic> diagnostics) {
        this.diagnostics = diagnostics;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> nonOptionArgs = args.getNonOptionArgs();
        String requestedProvider = nonOptionArgs.isEmpty() ? null : nonOptionArgs.get(0);

        if (diagnostics.isEmpty()) {
            log.warn("No FeedDiagnostic beans registered — no feed-distribution provider is enabled");
            return;
        }

        boolean anyRan = false;
        boolean anyUnhealthy = false;

        for (FeedDiagnostic diagnostic : diagnostics) {
            if (requestedProvider != null && !diagnostic.getProviderName().equals(requestedProvider)) {
                continue;
            }
            anyRan = true;
            DiagnosticResult result = diagnostic.check();
            if (result.healthy()) {
                log.info("[{}] OK: {}", diagnostic.getProviderName(), result.message());
            } else {
                anyUnhealthy = true;
                log.error("[{}] FAILED: {}", diagnostic.getProviderName(), result.message());
                if (result.docsUrl() != null) {
                    log.error("[{}]   see: {}", diagnostic.getProviderName(), result.docsUrl());
                }
            }
        }

        if (!anyRan) {
            log.warn("No FeedDiagnostic registered for provider '{}'", requestedProvider);
        } else if (anyUnhealthy) {
            log.error("One or more feed diagnostics failed — see above.");
        }
    }
}
