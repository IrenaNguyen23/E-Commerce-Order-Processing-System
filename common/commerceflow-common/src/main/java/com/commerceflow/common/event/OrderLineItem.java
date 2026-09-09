package com.commerceflow.common.event;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A single ordered line, carried on the saga events so participants never call back. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderLineItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID productId;
    private String sku;
    private String productName;
    private Integer quantity;
    private BigDecimal unitPrice;

    /** @return {@code unitPrice * quantity}, never {@code null}. */
    public BigDecimal subtotal() {
        if (unitPrice == null || quantity == null) {
            return BigDecimal.ZERO;
        }
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
