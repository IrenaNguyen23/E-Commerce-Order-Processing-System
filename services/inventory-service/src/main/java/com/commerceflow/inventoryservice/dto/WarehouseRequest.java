package com.commerceflow.inventoryservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Opening or editing a warehouse. */
@Schema(description = "A warehouse")
public record WarehouseRequest(

        @Schema(example = "MIL", requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Short stable identifier. Cannot be changed once the warehouse "
                        + "exists — it appears on paperwork and in every log line about the "
                        + "building, and changing it makes old records refer to nothing.")
        @NotBlank(message = "code is required")
        @Size(max = 20)
        String code,

        @Schema(example = "Milan distribution centre",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "name is required")
        @Size(max = 150)
        String name,

        @Schema(example = "IT", requiredMode = Schema.RequiredMode.REQUIRED,
                description = "ISO-3166 alpha-2. Checked before priority when choosing where an "
                        + "order ships from — inside the destination country avoids a customs "
                        + "form and usually a day.")
        @NotBlank(message = "countryCode is required")
        @Pattern(regexp = "^[A-Za-z]{2}$", message = "countryCode must be two letters")
        String countryCode,

        @Size(max = 100) String city,

        @Schema(description = "Lower goes first when more than one building could fill a line. "
                + "Defaults to 100.")
        Integer priority,

        @Schema(description = "Whether NEW orders may be allocated from here. Turning it off "
                + "leaves existing reservations alone.")
        Boolean active) {
}
