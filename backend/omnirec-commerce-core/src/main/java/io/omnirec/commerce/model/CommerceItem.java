// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/** One line of an order or cart. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommerceItem(
        String productId,
        Integer quantity,
        BigDecimal price,
        String currency,
        String categoryId
) {
    public static CommerceItem of(String productId, int quantity, BigDecimal price, String currency) {
        return new CommerceItem(productId, quantity, price, currency, null);
    }
}
