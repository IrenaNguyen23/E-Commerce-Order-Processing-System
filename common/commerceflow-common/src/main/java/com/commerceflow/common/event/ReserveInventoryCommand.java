package com.commerceflow.common.event;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Saga step 1, sent on {@code inventory.commands}: hold stock for every line of an order.
 *
 * <p>Answered with {@code inventory.reserved} or {@code inventory.failed}. The customer identity
 * and the payable amount travel along so they can be handed to the payment step without the
 * orchestrator or Payment Service ever calling back into Order Service.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class ReserveInventoryCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    private UUID userId;
    private String userEmail;
    private BigDecimal totalAmount;
    private String currency;
    private List<OrderLineItem> items;

    /**
     * ISO-3166 alpha-2 country the parcel is going to, or {@code null}.
     *
     * <p>Carried so Inventory can prefer a warehouse in the destination country — one fewer
     * customs form and usually a day faster. Optional, because an allocation without it is
     * still correct, only less good: the preference falls through to priority.
     */
    private String destinationCountry;
}
