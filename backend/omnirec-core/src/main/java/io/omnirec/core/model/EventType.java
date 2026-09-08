package io.omnirec.core.model;

/** Mirrors schema/canonical-event.schema.json's eventType enum. Keep in sync — CI's contract test in omnirec-contract-tests catches drift. */
public enum EventType {
    PRODUCT_VIEWED,
    PRODUCT_CLICKED,
    PRODUCT_DWELL,
    SCROLL_DEPTH,
    CART_ADD,
    CART_REMOVE,
    CART_ABANDONED,
    WISHLIST_ADD,
    SEARCH_QUERY,
    REVIEW_SUBMITTED,
    PURCHASE_COMPLETED,
    PAGE_VIEW
}
