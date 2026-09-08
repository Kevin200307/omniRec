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
    void syncCallsEveryActiveProvider() {
        CatalogSyncServiceImpl service = service(providerA, providerB);

        service.sync(List.of(ITEM)).join();

        verify(providerA).upsertItems(List.of(ITEM));
        verify(providerB).upsertItems(List.of(ITEM));
    }

    @Test
    void oneProviderPermanentlyFailingDoesNotPreventTheOtherFromCompleting() {
        when(providerA.getProviderName()).thenReturn("provider-a");
        doThrow(new RuntimeException("provider-a is down")).when(providerA).upsertItems(any());
        CatalogSyncServiceImpl service = service(providerA, providerB);

        // Must not throw, and must not leave providerB unsynced.
        service.sync(List.of(ITEM)).join();

        verify(providerB).upsertItems(List.of(ITEM));
        // providerA was still attempted the full retry budget (see next test for the exact count).
        verify(providerA, atLeastOnce()).upsertItems(any());
    }

    @Test
    void aTransientFailureIsRetriedAndEventuallySucceeds() {
        AtomicInteger attempts = new AtomicInteger(0);
        doAnswer(invocation -> {
            if (attempts.getAndIncrement() < 2) {
                throw new RuntimeException("transient network blip");
            }
            return null;
        }).when(providerA).upsertItems(any());
        CatalogSyncServiceImpl service = service(providerA);

        service.sync(List.of(ITEM)).join();

        // 2 failures + 1 success = 3 calls, matching maxAttempts.
        verify(providerA, times(3)).upsertItems(any());
    }

    @Test
    void aProviderThatNeverRecoversIsCalledExactlyMaxAttemptsTimesThenGivenUp() {
        when(providerA.getProviderName()).thenReturn("provider-a");
        doThrow(new RuntimeException("permanently down")).when(providerA).upsertItems(any());
        CatalogSyncServiceImpl service = service(providerA);

        service.sync(List.of(ITEM)).join();

        verify(providerA, times(3)).upsertItems(any());
    }

    @Test
    void removeFansOutToEveryProviderWithTheGivenIds() {
        CatalogSyncServiceImpl service = service(providerA, providerB);

        service.remove("sku-1", "sku-2").join();

        verify(providerA).removeItems(List.of("sku-1", "sku-2"));
        verify(providerB).removeItems(List.of("sku-1", "sku-2"));
    }

    @Test
    void syncWithNoProvidersIsANoOpAndNeverThrows() {
        CatalogSyncServiceImpl service = new CatalogSyncServiceImpl(List.of(), Runnable::run, fastRetryTemplate());

        service.sync(List.of(ITEM)).join();
    }

    @Test
    void syncWithEmptyItemsSkipsEveryProviderEntirely() {
        CatalogSyncServiceImpl service = service(providerA, providerB);

        service.sync(List.of()).join();

        verifyNoInteractions(providerA, providerB);
    }
}
