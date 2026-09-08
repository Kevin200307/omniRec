package io.omnirec.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * No Spring context anywhere in this test — that's the payoff of using
 * RetryTemplate programmatically instead of @Retryable (see
 * CatalogSyncServiceImpl's javadoc). The executor below runs tasks
 * synchronously on the calling thread: this test isn't asserting wall-clock
 * parallelism (that's provided by whatever real Executor bean production
 * wires in), it's asserting the fan-out/isolation/retry *logic*, which a
 * synchronous executor exercises identically and deterministically.
 */
@ExtendWith(MockitoExtension.class)
class CatalogSyncServiceImplTest {

    private static final CatalogItem ITEM = new CatalogItem(
            "sku-1", "Widget", "A widget", new BigDecimal("9.99"), "widgets", "http://example.com/w.png", null, Instant.now()
    );

    @Mock
    private CatalogProvider providerA;

    @Mock
    private CatalogProvider providerB;

    private RetryTemplate fastRetryTemplate() {
        RetryTemplate template = new RetryTemplate();
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(3);
        template.setRetryPolicy(retryPolicy);
        FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(1L);
        template.setBackOffPolicy(backOffPolicy);
        return template;
    }

    private CatalogSyncServiceImpl service(CatalogProvider... providers) {
        return new CatalogSyncServiceImpl(List.of(providers), Runnable::run, fastRetryTemplate());
    }

    @Test
    void syncCallsEveryActiveProviderAndReturnsOneResultEach() {
        when(providerA.upsertItems(List.of(ITEM))).thenReturn(new SyncResult("provider-a", 1, 0, List.of()));
        when(providerB.upsertItems(List.of(ITEM))).thenReturn(new SyncResult("provider-b", 1, 0, List.of()));
        CatalogSyncServiceImpl service = service(providerA, providerB);

        List<SyncResult> results = service.sync(List.of(ITEM)).join();

        verify(providerA).upsertItems(List.of(ITEM));
        verify(providerB).upsertItems(List.of(ITEM));
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(r -> r.providerName().equals("provider-a")));
        assertTrue(results.stream().anyMatch(r -> r.providerName().equals("provider-b")));
    }

    @Test
    void oneProviderPermanentlyFailingDoesNotPreventTheOtherFromCompleting() {
        when(providerA.getProviderName()).thenReturn("provider-a");
        doThrow(new RuntimeException("provider-a is down")).when(providerA).upsertItems(any());
        when(providerB.upsertItems(List.of(ITEM))).thenReturn(new SyncResult("provider-b", 1, 0, List.of()));
        CatalogSyncServiceImpl service = service(providerA, providerB);

        // Must not throw, and must not leave providerB unsynced.
        List<SyncResult> results = service.sync(List.of(ITEM)).join();

        verify(providerB).upsertItems(List.of(ITEM));
        // providerA was still attempted the full retry budget (see next test for the exact count).
        verify(providerA, atLeastOnce()).upsertItems(any());
        assertEquals(2, results.size(), "a permanently-failing provider still produces a SyncResult, not a dropped entry");
    }

    @Test
    void aTransientFailureIsRetriedAndEventuallySucceeds() {
        AtomicInteger attempts = new AtomicInteger(0);
        doAnswer(invocation -> {
            if (attempts.getAndIncrement() < 2) {
                throw new RuntimeException("transient network blip");
            }
            return new SyncResult("provider-a", 1, 0, List.of());
        }).when(providerA).upsertItems(any());
        CatalogSyncServiceImpl service = service(providerA);

        List<SyncResult> results = service.sync(List.of(ITEM)).join();

        // 2 failures + 1 success = 3 calls, matching maxAttempts.
        verify(providerA, times(3)).upsertItems(any());
        assertEquals(1, results.get(0).accepted(), "the eventually-successful attempt's real result must be what's returned");
        assertEquals(0, results.get(0).rejected());
    }

    @Test
    void aProviderThatNeverRecoversIsCalledExactlyMaxAttemptsTimesThenProducesASyntheticWholeBatchRejection() {
        when(providerA.getProviderName()).thenReturn("provider-a");
        doThrow(new RuntimeException("permanently down")).when(providerA).upsertItems(any());
        CatalogSyncServiceImpl service = service(providerA);

        List<SyncResult> results = service.sync(List.of(ITEM)).join();

        verify(providerA, times(3)).upsertItems(any());
        SyncResult result = results.get(0);
        assertEquals("provider-a", result.providerName());
        assertEquals(0, result.accepted());
        assertEquals(1, result.rejected());
        assertEquals(1, result.rejections().size());
        assertEquals("*", result.rejections().get(0).productId(), "a whole-provider failure uses the '*' sentinel, not a fabricated per-item reason");
    }

    /**
     * A returned SyncResult — even one entirely made of rejections — is not
     * an exception, so it must never trigger a retry: retrying a validation
     * failure like "missing GTIN" doesn't fix it. This is the crux of the
     * retry-vs-rejection distinction the whole SyncResult design exists for.
     */
    @Test
    void aSyncResultWithRejectionsIsReturnedAsIsAndNeverRetried() {
        SyncResult partial = new SyncResult("provider-a", 0, 1, List.of(new RejectedItem("sku-1", "missing GTIN")));
        when(providerA.upsertItems(List.of(ITEM))).thenReturn(partial);
        CatalogSyncServiceImpl service = service(providerA);

        List<SyncResult> results = service.sync(List.of(ITEM)).join();

        verify(providerA, times(1)).upsertItems(List.of(ITEM));
        assertEquals(partial, results.get(0));
    }

    @Test
    void removeFansOutToEveryProviderWithTheGivenIds() {
        when(providerA.removeItems(List.of("sku-1", "sku-2"))).thenReturn(new SyncResult("provider-a", 2, 0, List.of()));
        when(providerB.removeItems(List.of("sku-1", "sku-2"))).thenReturn(new SyncResult("provider-b", 2, 0, List.of()));
        CatalogSyncServiceImpl service = service(providerA, providerB);

        service.remove("sku-1", "sku-2").join();

        verify(providerA).removeItems(List.of("sku-1", "sku-2"));
        verify(providerB).removeItems(List.of("sku-1", "sku-2"));
    }

    @Test
    void syncWithNoProvidersIsANoOpAndReturnsAnEmptyList() {
        CatalogSyncServiceImpl service = new CatalogSyncServiceImpl(List.of(), Runnable::run, fastRetryTemplate());

        List<SyncResult> results = service.sync(List.of(ITEM)).join();

        assertTrue(results.isEmpty());
    }

    @Test
    void syncWithEmptyItemsSkipsEveryProviderEntirely() {
        CatalogSyncServiceImpl service = service(providerA, providerB);

        service.sync(List.of()).join();

        verifyNoInteractions(providerA, providerB);
    }

    /**
     * Architectural guarantee, not just a runtime check: CatalogSyncServiceImpl's
     * constructor has no FeedFileProvider parameter at all — there is no code
     * path by which sync()/remove() could reach one. FeedFileProvider beans
     * are exclusively driven by ScheduledFeedPublisher, on its own schedule.
     * See ScheduledFeedPublisherTest for that side of the split.
     */
    @Test
    void catalogSyncServiceImplHasNoWayToReferenceFeedFileProviderBeans() {
        assertEquals(3, CatalogSyncServiceImpl.class.getDeclaredConstructors()[0].getParameterCount());
        for (Class<?> paramType : CatalogSyncServiceImpl.class.getDeclaredConstructors()[0].getParameterTypes()) {
            assertNotEquals(FeedFileProvider.class, paramType);
            assertNotEquals(List.class.getName() + "<" + FeedFileProvider.class.getName() + ">", paramType.getName());
        }
    }
}
