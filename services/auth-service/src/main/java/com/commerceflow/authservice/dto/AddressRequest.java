package com.commerceflow.authservice.dto;

import com.commerceflow.authservice.entity.AddressType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Creating or replacing an address.
 *
 * <p>{@code line2}, {@code region} and {@code postalCode} are optional because a validator that
 * demands them rejects real addresses: Ireland had no postcodes at all until recently, and plenty
 * of countries have no administrative region worth writing down. Requiring a field the world does
 * not have produces customers who type "n/a" into it.
 */
@Schema(description = "A postal address")
public record AddressRequest(

        @Schema(example = "Home") @Size(max = 50) String label,

        @Schema(description = "SHIPPING, BILLING or BOTH; defaults to BOTH")
        AddressType type,

        @Schema(example = "Ada Lovelace", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "recipientName is required")
        @Size(max = 150) String recipientName,

        @Schema(example = "+31 20 123 4567") @Size(max = 32) String phone,

        @Schema(example = "Keizersgracht 1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "line1 is required")
        @Size(max = 200) String line1,

        @Schema(example = "Floor 3") @Size(max = 200) String line2,

        @Schema(example = "Amsterdam", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "city is required")
        @Size(max = 100) String city,

        @Schema(example = "Noord-Holland") @Size(max = 100) String region,

        @Schema(example = "1015 CJ") @Size(max = 20) String postalCode,

        @Schema(example = "NL", requiredMode = Schema.RequiredMode.REQUIRED,
                description = "ISO-3166 alpha-2. Decides the tax rate and the shipping zone.")
        @NotBlank(message = "countryCode is required")
        @Pattern(regexp = "^[A-Za-z]{2}$", message = "countryCode must be two letters, e.g. NL")
        String countryCode,

        @Schema(description = "Make this the address the checkout form pre-selects")
        Boolean makeDefault) {
}
