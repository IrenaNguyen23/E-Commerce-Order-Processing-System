package com.commerceflow.orderservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How an order travels, and the window that was quoted when the customer agreed to pay.
 *
 * <p>A range rather than a date, because that is what was actually promised. Turning
 * "2 to 4 working days" into a single date on the way out would be inventing a commitment nobody
 * in this system is in a position to make.
 */
@Schema(description = "The delivery method and the window quoted at checkout")
public record DeliverySummary(
        @Schema(example = "STANDARD") String method,
        @Schema(description = "Fastest quoted working days; 0 for collection") Integer minDays,
        @Schema(description = "Slowest quoted working days; 0 for collection") Integer maxDays) {
}
