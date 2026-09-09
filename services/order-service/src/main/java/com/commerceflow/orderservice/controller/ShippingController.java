package com.commerceflow.orderservice.controller;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.commerceflow.common.dto.ApiResponse;
import com.commerceflow.orderservice.pricing.ShippingQuote;
import com.commerceflow.orderservice.pricing.ShippingQuoteService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;

/**
 * What delivery will cost, before the customer commits to anything.
 *
 * <p>Exists so the checkout page can show the real total — goods, delivery and tax — while the
 * customer is still choosing. A total that appears only after the order is placed is a total the
 * customer did not agree to.
 *
 * <p>This endpoint quotes; it does not reserve or promise. The order is priced again when it is
 * placed, from the same rate card, and that second calculation is the one that counts. Quoting and
 * charging from one service and one table is what keeps the two answers the same.
 */
@Validated
@RestController
@RequestMapping("/api/shipping")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Shipping", description = "Delivery options and what they cost")
public class ShippingController {

    private final ShippingQuoteService quoteService;

    @GetMapping("/quotes")
    @Operation(summary = "Price the delivery options for a destination",
            description = """
                    Returns every method that can actually be offered to that country, cheapest \
                    first, priced for this basket. A method with no rate is left out rather than \
                    shown and then refused on submit.

                    `goodsAfterDiscount` is what the customer is paying for the goods. The \
                    free-delivery threshold is measured against it rather than the list price, so \
                    a coupon cannot buy free delivery.""")
    public ResponseEntity<ApiResponse<List<ShippingQuote>>> quotes(
            @Parameter(description = "ISO-3166 alpha-2 destination", example = "NL")
            @RequestParam String countryCode,

            @Parameter(description = "How many units, for the per-item element of the rate")
            @RequestParam(defaultValue = "1") @Min(1) int itemCount,

            @Parameter(description = "Basket value after discounts")
            @RequestParam(defaultValue = "0") BigDecimal goodsAfterDiscount,

            @RequestParam(defaultValue = "EUR") String currency) {

        return ResponseEntity.ok(ApiResponse.ok(quoteService.options(
                countryCode, itemCount, goodsAfterDiscount, currency)));
    }
}
