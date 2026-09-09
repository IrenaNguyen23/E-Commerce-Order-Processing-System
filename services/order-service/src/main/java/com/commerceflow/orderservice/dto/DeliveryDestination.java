package com.commerceflow.orderservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Where an order is going, as the order itself remembers it.
 *
 * <p>Returned alongside the formatted one-liner rather than instead of it. The formatted string is
 * what was frozen at checkout and is what belongs on a label; these fields are what a client needs
 * to pre-fill a form or show a country flag, and re-parsing them out of the one-liner is the kind
 * of thing that works until an address has a comma in it.
 */
@Schema(description = "The destination, as it was recorded at checkout")
public record DeliveryDestination(
        String recipientName,
        String phone,
        String line1,
        String line2,
        String city,
        String region,
        String postalCode,
        @Schema(description = "ISO-3166 alpha-2. This is what chose the tax and delivery rate.")
        String countryCode) {
}
