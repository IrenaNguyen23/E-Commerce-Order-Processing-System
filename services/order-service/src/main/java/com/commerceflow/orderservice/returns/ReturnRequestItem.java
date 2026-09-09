package com.commerceflow.orderservice.returns;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One order line coming back, in whole or in part.
 *
 * <p>The product name and SKU are copied rather than joined, exactly as the order line copied them
 * from the catalogue. A product renamed since the order was placed must not rename itself on the
 * return paperwork a warehouse is holding.
 */
@Entity
@Table(name = "return_request_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnRequestItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_request_id", nullable = false)
    private ReturnRequest returnRequest;

    /** The order line this came from. What a second request checks against. */
    @Column(name = "order_item_id", nullable = false)
    private UUID orderItemId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /**
     * What this line gives back: net at the price actually charged, plus tax at the rate frozen
     * on the order.
     *
     * <p>Computed per line and rounded once — never apportioned back out of the order total,
     * which is the rule the order itself follows and how a cent goes missing when it is broken.
     */
    @Column(name = "refund_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal refundAmount;
}
