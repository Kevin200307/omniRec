package io.omnirec.catalog.diagnostics;

/**
 * A lightweight, read-only pre-flight check for a feed-distribution
 * provider — implemented by GoogleMerchantDiagnostic and
 * OpenAIFeedDiagnostic, run by FeedDiagnosticRunner. Never attempts a real
 * catalog sync; the whole point is catching "merchant account not approved
 * yet" or "wrong credentials" in seconds, before a scheduled job fails
 * silently at 3am — see FeedDiagnosticRunner's javadoc.
 */
public interface FeedDiagnostic {

    /** Provider id used to select which diagnostic(s) to run — e.g. "google-merchant", "openai-feed". */
    String getProviderName();

    DiagnosticResult check();
}
