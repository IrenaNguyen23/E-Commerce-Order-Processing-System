package com.commerceflow.inventoryservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the demo catalogue against the two ways it could fail silently.
 *
 * <p>Neither is caught by the compiler, and both would surface as a container that crashes on
 * start-up or a storefront full of broken images — a bad first five minutes for anyone trying the
 * project out.
 */
class DemoCatalogueInitializerTest {

    /** {@code products.image_url} is {@code VARCHAR(500)}. */
    private static final int IMAGE_URL_COLUMN_LENGTH = 500;

    @Test
    @DisplayName("every placeholder image fits the image_url column")
    void placeholderImagesFitTheColumn() {
        assertThat(DemoCatalogueInitializer.allImageUrls())
                .isNotEmpty()
                .allSatisfy(url -> assertThat(url.length())
                        .as("placeholder image is longer than the column: %s", url)
                        .isLessThanOrEqualTo(IMAGE_URL_COLUMN_LENGTH));
    }

    @Test
    @DisplayName("the placeholder is a well formed data URI with no raw quotes")
    void placeholderIsAWellFormedDataUri() {
        String url = DemoCatalogueInitializer.placeholderImage("1e293b", "L16");

        assertThat(url).startsWith("data:image/svg+xml,");
        // A literal quote is invalid in a URI, and would need escaping in SQL if this ever moved
        // into a migration. Percent-encoded is the only form that is safe in both places.
        assertThat(url).doesNotContain("\"").doesNotContain("'");
        assertThat(url).contains("%231e293b").contains("%3EL16%3C/text%3E");
    }
}
