package com.commerceflow.common.address;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A postal address, shared by every service that has to print one or price one.
 *
 * <p>It lives in {@code common} for one reason: the string a customer sees on their order
 * confirmation, the string on the shipping label and the string on the invoice have to be the
 * same string. Three services formatting the same fields three ways is not a cosmetic problem — it
 * is how a support conversation ends up comparing two addresses that are actually identical.
 *
 * <p>This is a transport and formatting type. Services store the fields in their own columns; only
 * the shape and the formatting are shared.
 *
 * <h2>Why so few fields are required</h2>
 *
 * <p>Only recipient, line 1, city and country are mandatory. Postcodes and regions are optional
 * because a large part of the world does not have them, and a form that insists produces
 * "N/A" typed into a field that a courier will later try to read.
 */
public record PostalAddress(

        @NotBlank(message = "recipientName is required")
        @Size(max = 150) String recipientName,

        @Size(max = 32) String phone,

        @NotBlank(message = "line1 is required")
        @Size(max = 200) String line1,

        @Size(max = 200) String line2,

        @NotBlank(message = "city is required")
        @Size(max = 100) String city,

        @Size(max = 100) String region,

        @Size(max = 20) String postalCode,

        @NotBlank(message = "countryCode is required")
        @Pattern(regexp = "^[A-Za-z]{2}$", message = "countryCode must be two letters, e.g. NL")
        String countryCode) {

    /**
     * Upper-cases the country code so everything downstream can compare it directly.
     *
     * <p>{@link Locale#ROOT} rather than the default locale: under a Turkish locale
     * {@code "in".toUpperCase()} is {@code "İN"}, and India would quietly stop matching any tax
     * rate — on some servers and not others.
     */
    public PostalAddress {
        if (countryCode != null) {
            countryCode = countryCode.trim().toUpperCase(Locale.ROOT);
        }
    }

    /** The upper-cased country code, or {@code null}. */
    public String country() {
        return countryCode;
    }

    /**
     * One line, suitable for a label, a confirmation email or a support screen.
     *
     * <p>Blank parts are dropped rather than leaving the double commas that give away a template.
     */
    public String formatted() {
        List<String> parts = new ArrayList<>();
        add(parts, recipientName);
        add(parts, line1);
        add(parts, line2);

        StringBuilder locality = new StringBuilder();
        if (isPresent(postalCode)) {
            locality.append(postalCode.trim()).append(' ');
        }
        if (isPresent(city)) {
            locality.append(city.trim());
        }
        add(parts, locality.toString());

        add(parts, region);
        add(parts, countryCode);
        return String.join(", ", parts);
    }

    private static void add(List<String> parts, String value) {
        if (isPresent(value)) {
            parts.add(value.trim());
        }
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
