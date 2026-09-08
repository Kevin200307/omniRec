package io.omnirec.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Fans out to every currently-enabled CatalogProvider bean — Spring injects
 * only the ones whose @ConditionalOnProperty check passed, so this class
 * never knows or cares whether Algolia, Personalize, both, or neither are
 * active. Retry is deliberately centralized here (via RetryTemplate, used
 * programmatically rather than Spring's @Retryable) rather than duplicated
 * in each provider adapter:
 *   - @Retryable relies on AOP proxying and silently no-ops on
 *     self-invocation; a hand-rolled RetryTemplate call has no such trap.
 *   - It means AlgoliaCatalogProvider / PersonalizeCatalogProvider / a
 *     future GoogleRecAiCatalogProvider only ever implement the mapping +
 *     the raw SDK call — resilience policy is a single, uniformly-applied
 *     concern, not something every new adapter has to remember to add.
 *   - It's unit-testable with plain Mockito, no Spring context required.
 */
public class CatalogSyncServiceImpl implements CatalogSyncService {

    private static final Logger log = LoggerFactory.getLogger(CatalogSyncServiceImpl.class);

    private final List<CatalogProvider> providers;
    private final Executor executor;
    private final RetryTemplate retryTemplate;

    public CatalogSyncServiceImpl(List<CatalogProvider> providers, Executor executor, RetryTemplate retryTemplate) {
        this.providers = providers;
        this.executor = executor;
        this.retryTemplate = retryTemplate;
    }

    @Override
    public CompletableFuture<Void> sync(List<CatalogItem> items) {
        if (items == null || items.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatch("upsertItems", provider -> provider.upsertItems(items));
    }

    @Override
    public CompletableFuture<Void> remove(String... productIds) {
        if (productIds == null || productIds.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        List<String> ids = List.of(productIds);
        return dispatch("removeItems", provider -> provider.removeItems(ids));
    }

    /**
     * Runs {@code operation} against every active provider in parallel on
     * {@code executor} — one CompletableFuture per provider, so a slow or
     * retrying provider never delays another. The returned future is the
     * join of all of them but, critically, never completes exceptionally:
     * a provider that exhausts its retries is caught, logged, and skipped
     * inside runWithRetry — by the time allOf() sees these futures, every
     * one of them has already completed normally.
     */
    private CompletableFuture<Void> dispatch(String operationName, Consumer<CatalogProvider> operation) {
        if (providers.isEmpty()) {
            log.debug("No CatalogProvider beans active — {} is a no-op", operationName);
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<?>[] futures = providers.stream()
                .map(provider -> CompletableFuture.runAsync(() -> runWithRetry(provider, operationName, operation), executor))
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures);
    }

    private void runWithRetry(CatalogProvider provider, String operationName, Consumer<CatalogProvider> operation) {
        try {
            retryTemplate.execute(context -> {
                operation.accept(provider);
                return null;
            });
        } catch (Exception e) {
            log.error(
                    "Catalog {} failed for provider [{}] after all retry attempts — skipping this provider for this batch",
                    operationName, provider.getProviderName(), e
            );
        }
    }

    /** 3 attempts, exponential backoff (200ms, 400ms, 800ms). Used by the auto-configuration bean unless a developer supplies their own RetryTemplate bean. */
    public static RetryTemplate defaultRetryTemplate() {
        RetryTemplate template = new RetryTemplate();

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(3);
        template.setRetryPolicy(retryPolicy);

        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(200);
        backOffPolicy.setMultiplier(2.0);
        backOffPolicy.setMaxInterval(2000);
        template.setBackOffPolicy(backOffPolicy);

        return template;
    }
}
