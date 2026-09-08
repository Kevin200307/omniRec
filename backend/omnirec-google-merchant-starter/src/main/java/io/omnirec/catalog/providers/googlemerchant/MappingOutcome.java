package io.omnirec.catalog.providers.googlemerchant;

import java.util.Map;

/** Result of GoogleMerchantItemMapper.map() — validation happens before any API/file call is made, in either mode. */
public sealed interface MappingOutcome {

    /** Merchant field name -> value, e.g. "title" -> "Widget", ready for either Product-resource construction (push) or a TSV row (scheduled-fetch). */
    record Mapped(Map<String, String> attributes) implements MappingOutcome {}

    record Rejected(String productId, String reason) implements MappingOutcome {}
}
