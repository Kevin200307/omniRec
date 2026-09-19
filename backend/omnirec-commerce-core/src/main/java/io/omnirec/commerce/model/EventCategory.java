package io.omnirec.commerce.model;

/** Groups the taxonomy for routing and metrics. Never sent to a provider. */
public enum EventCategory {
    SESSION,
    DISCOVERY,
    PRODUCT_INTERACTION,
    CART,
    CHECKOUT,
    PURCHASE,
    RECOMMENDATION,
    USER,
    IDENTITY
}
