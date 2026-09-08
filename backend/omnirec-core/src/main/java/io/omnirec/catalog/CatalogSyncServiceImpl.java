package io.omnirec.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Fans out to every currently-enabled CatalogProvider bean — Spring injects
 * only the ones whose @ConditionalOnProperty check passed, so this class
 * never knows or cares whether Algolia, Personalize, Google Merchant push
 * mode, all three, or none are active. FeedFileProvider beans are never
 * touched here — see ScheduledFeedPublisher.
 *
 * Retry (RetryTemplate, used programmatically — see the original design
 * rationale in this class's git history for why not @Retryable) fires only
 * on a *thrown* exception from a provider call, e.g. a network timeout.
 * A provider that returns a SyncResult with rejections — even a lot of
 * them — is not retried; those are permanent, already-decided outcomes
 * (retrying "missing GTIN" doesn't fix it), and each adapter is responsible
 * for catching its own per-chunk failures and reporting them as
 * RejectedItems rather than throwing. Retry here is a backstop for
 * transient, whole-call failures, not a substitute for adapters reporting
 * accurately.
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
    public CompletableFuture<List<SyncResult>> sync(List<CatalogItem> items) {
        if (items == null || items.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return dispatch("upsertItems", items.size(), provider -> provider.upsertItems(items));
    }

    @Override
    public CompletableFuture<List<SyncResult>> remove(String... productIds) {
        if (productIds == null || productIds.length == 0) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<String> ids = List.of(productIds);
        return dispatch("removeItems", ids.size(), provider -> provider.removeItems(ids));
    }

    /**
     * Runs {@code operation} against every active provider in parallel on
     * {@code executor} — one CompletableFuture per provider, so a slow or
     * retrying provider never delays another. The returned future is the
     * join of all of them but, critically, never completes exceptionally —
     * runWithRetry always resolves to a SyncResult, even for a provider
     * that fails every retry attempt (see its javadoc for the synthetic
     * whole-batch-failure result it produces in that case).
     */
    private CompletableFuture<List<SyncResult>> dispatch(String operationName, int batchSize, Function<CatalogProvider, SyncResult> operation) {
        if (providers.isEmpty()) {
            log.debug("No CatalogProvider beans active — {} is a no-op", operationName);
            return CompletableFuture.completedFuture(List.of());
        }

        List<CompletableFuture<SyncResult>> futures = providers.stream()
                .map(provider -> CompletableFuture.supplyAsync(() -> runWithRetry(provider, operationName, batchSize, operation), executor))
                .toList();

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(v -> futures.stream().map(CompletableFuture::join).toList());
    }

    private SyncResult runWithRetry(CatalogProvider provider, String operationName, int batchSize, Function<CatalogProvider, SyncResult> operation) {
        try {
            return retryTemplate.execute(context -> operation.apply(provider));
        } catch (Exception e) {
            log.error(
                    "Catalog {} failed for provider [{}] after all retry attempts — nothing in this batch reached it",
                    operationName, provider.getProviderName(), e
            );
            return new SyncResult(
                    provider.getProviderName(), 0, batchSize,
                    List.of(new RejectedItem("*", "provider unreachable after retries: " + e.getMessage()))
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
