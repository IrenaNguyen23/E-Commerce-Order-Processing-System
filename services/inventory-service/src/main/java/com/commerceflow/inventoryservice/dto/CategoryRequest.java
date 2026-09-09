package com.commerceflow.inventoryservice.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Creating or editing a catalogue section. */
@Schema(description = "A catalogue category")
public record CategoryRequest(

        @Schema(example = "Computers", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "name is required")
        @Size(max = 100)
        String name,

        @Schema(description = "Stable identifier used in URLs and by the tax rate card. Derived "
                + "from the name when omitted, and refused on update — renaming the slug of a "
                + "live category silently changes what tax its products attract.",
                example = "computers")
        @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                message = "slug must be lower case words separated by hyphens")
        @Size(max = 100)
        String slug,

        @Size(max = 500) String description,

        @Schema(description = "Parent section, for a subsection. One level only.")
        UUID parentId,

        @Schema(description = "Where it appears in a menu; lower comes first")
        Integer position,

        @Size(max = 500) String imageUrl,

        @Schema(description = "Hidden from the storefront when false. Defaults to true.")
        Boolean active) {
}
