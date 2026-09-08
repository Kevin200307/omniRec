package io.omnirec.catalog;

import java.util.List;

/**
 * Implemented by the consuming application, not by Omnirec — the one thing
 * a developer supplies to use ScheduledFeedPublisher. Returns the store's
 * entire current catalog; called once per scheduled feed-refresh run.
 *
 * If omnirec.catalog.feed-sync-enabled=true and no CatalogSource bean
 * exists, the application deliberately fails to start (a missing-bean
 * error) rather than silently never publishing feeds — a developer who
 * turned this on needs to know their scheduled job has nothing to run.
 */
public interface CatalogSource {

    List<CatalogItem> fetchAll();
}
