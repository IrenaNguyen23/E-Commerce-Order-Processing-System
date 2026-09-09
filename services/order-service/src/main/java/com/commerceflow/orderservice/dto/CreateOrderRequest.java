package com.commerceflow.orderservice.dto;

import java.util.List;

import com.commerceflow.common.address.PostalAddress;
import com.commerceflow.orderservice.pricing.ShippingMethod;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload of {@code POST /api/orders}.
 *
 * <h2>The address is structured, and that is a breaking change</h2>
 *
 * <p>{@code shippingAddress} used to be a single line of text. It is now an object, because the
 * destination country decides the tax rate and the delivery charge, and a country parsed out of
 * free text is a country that will eventually be parsed wrong — quietly, into a wrong total.
 *
 * <p>Clients send the address rather than an id from the customer's address book on purpose: the
 * order keeps its own copy, so editing or deleting a saved address later cannot disturb an order
 * that has already been placed.
 */
@Schema(description = "New order")
public record CreateOrderRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "an order must contain at least one item")
        @Size(max = 50, message = "an order may not contain more than 50 lines")
        @Valid
        List<OrderItemRequest> items,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Where it is going. The country code decides tax and delivery cost.")
        @NotNull(message = "shippingAddress is required")
        @Valid
        PostalAddress shippingAddress,

        @Schema(description = "STANDARD, EXPRESS or PICKUP. Defaults to STANDARD.",
                example = "STANDARD")
        ShippingMethod shippingMethod,

        @Schema(description = "A discount code, if the customer has one. Claimed at checkout, "
                + "so a code that passed the basket preview can still be refused here if the "
                + "last one went in between.",
                example = "WELCOME10")
        @Size(max = 40)
        String couponCode,

        @Schema(description = "Optional client generated key; replaying it returns the first order "
                + "instead of creating a second one",
                example = "b7f1c2e4-0f2a-4f2a-9c3b-2f7f7c1d8e10")
        @Size(max = 64)
        String idempotencyKey) {
}
