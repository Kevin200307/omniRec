package io.omnirec.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * publishFeeds() is called directly — @PostConstruct never fires outside a
 * Spring container, so no real timer runs in this test, matching the
 * pattern in CatalogSyncServiceImplTest.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledFeedPublisherTest {

    private static final CatalogItem ITEM = new CatalogItem(
            "sku-1", "Widget", "A widget", new BigDecimal("9.99"), "widgets", "http://example.com/w.png", null, Instant.now()
    );

    @Mock
    private CatalogSource catalogSource;

    @Mock
    private FeedFileProvider providerA;

    @Mock
    private FeedFileProvider providerB;

    @Test
    void fetchesTheCatalogExactlyOnceAndPassesTheSameSnapshotToEveryProvider() {
        List<CatalogItem> catalog = List.of(ITEM);
        when(catalogSource.fetchAll()).thenReturn(catalog);
        when(providerA.generateAndPublish(catalog)).thenReturn(new SyncResult("provider-a", 1, 0, List.of()));
        when(providerB.generateAndPublish(catalog)).thenReturn(new SyncResult("provider-b", 1, 0, List.of()));
        ScheduledFeedPublisher publisher = new ScheduledFeedPublisher(List.of(providerA, providerB), catalogSource, Duration.ofMinutes(15));

        publisher.publishFeeds();

        verify(catalogSource, times(1)).fetchAll();
        verify(providerA).generateAndPublish(catalog);
        verify(providerB).generateAndPublish(catalog);
    }

    @Test
    void oneProviderThrowingDoesNotPreventTheOtherFromRunningInTheSameRun() {
        when(catalogSource.fetchAll()).thenReturn(List.of(ITEM));
        when(providerA.getProviderName()).thenReturn("provider-a");
        when(providerA.generateAndPublish(any())).thenThrow(new RuntimeException("endpoint unreachable"));
        when(providerB.generateAndPublish(any())).thenReturn(new SyncResult("provider-b", 1, 0, List.of()));
        ScheduledFeedPublisher publisher = new ScheduledFeedPublisher(List.of(providerA, providerB), catalogSource, Duration.ofMinutes(15));

        List<SyncResult> results = publisher.publishFeeds();

        verify(providerB).generateAndPublish(any());
        assertEquals(2, results.size(), "a provider that throws still produces a SyncResult entry, not a dropped run");
        assertTrue(results.stream().anyMatch(r -> r.providerName().equals("provider-a") && r.rejected() == 1));
        assertTrue(results.stream().anyMatch(r -> r.providerName().equals("provider-b") && r.accepted() == 1));
    }

    @Test
    void noRetryIsAttempted() {
        when(catalogSource.fetchAll()).thenReturn(List.of(ITEM));
        when(providerA.getProviderName()).thenReturn("provider-a");
        when(providerA.generateAndPublish(any())).thenThrow(new RuntimeException("down"));
        ScheduledFeedPublisher publisher = new ScheduledFeedPublisher(List.of(providerA), catalogSource, Duration.ofMinutes(15));

        publisher.publishFeeds();

        verify(providerA, times(1)).generateAndPublish(any());
    }

    @Test
    void aRunWithNoFeedProvidersStillFetchesNothingAndReturnsAnEmptyList() {
        ScheduledFeedPublisher publisher = new ScheduledFeedPublisher(List.of(), catalogSource, Duration.ofMinutes(15));

        List<SyncResult> results = publisher.publishFeeds();

        assertTrue(results.isEmpty());
        verify(catalogSource).fetchAll();
    }
}
