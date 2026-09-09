package com.commerceflow.inventoryservice.review;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Writing or replacing a review. */
@Schema(description = "A product review")
public record ReviewRequest(

        @Schema(example = "5", requiredMode = Schema.RequiredMode.REQUIRED,
                description = "One to five. Anything else is refused rather than clamped.")
        @Min(value = 1, message = "A rating is between 1 and 5")
        @Max(value = 5, message = "A rating is between 1 and 5")
        int rating,

        @Schema(example = "Does exactly what I needed")
        @Size(max = 150) String title,

        @Size(max = 4000) String body,

        @Schema(description = "How you want to be shown. Left blank, only an initial is "
                + "published — an email address is not something anybody consents to publish by "
                + "writing a review.",
                example = "Ada L.")
        @Size(max = 100) String authorName) {
}
