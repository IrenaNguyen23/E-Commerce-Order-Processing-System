package com.commerceflow.inventoryservice.entity;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A section of the catalogue.
 *
 * <h2>Why a table rather than a string on the product</h2>
 *
 * <p>A free-text category is four categories: "Computers", "computers", "COMPUTERS" and
 * "Comptuers". Nobody notices until a customer filters by one of them and finds a third of the
 * range missing. A row means the name exists once, can be renamed in one place, and can carry the
 * things a category actually needs — an order to appear in, a description, an image.
 *
 * <h2>The slug is the identity; the name is the label</h2>
 *
 * <p>{@link #slug} is stable, lower case, and what everything keys on: URLs, tax rates, saved
 * filters. {@link #name} is what a customer reads and may be changed at will.
 *
 * <p>That separation is load-bearing rather than tidy. Tax rates are looked up by category, and
 * if they keyed on the display name then renaming "Computers" to "Laptops &amp; desktops" would
 * silently change the tax charged on everything in it — no error, no failed request, just a
 * different number on the next invoice.
 *
 * <h2>One level of nesting, and no more</h2>
 *
 * <p>{@link #parentId} allows a child category and stops there: the service refuses to give a
 * child its own children. Arbitrary depth sounds more general and buys nothing here — it turns
 * every breadcrumb, every filter and every "products in this category" query into a recursive one,
 * to model a shop that has sections and subsections.
 */
@Entity
@Table(name = "categories", indexes = {
        @Index(name = "idx_categories_slug", columnList = "slug", unique = true),
        @Index(name = "idx_categories_parent", columnList = "parent_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Category {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Stable, lower case, hyphenated. Never changes once products point at it. */
    @Column(name = "slug", nullable = false, unique = true, length = 100)
    private String slug;

    /** What the customer reads. Safe to change. */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    /** Null for a top-level section. At most one level deep; see the class comment. */
    @Column(name = "parent_id")
    private UUID parentId;

    /**
     * Where it sits in a menu.
     *
     * <p>Explicit rather than alphabetical, because the order a shop wants its sections in is a
     * merchandising decision, not a property of their names.
     */
    @Column(name = "position", nullable = false)
    @Builder.Default
    private int position = 0;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /**
     * Hidden from the storefront when false.
     *
     * <p>Deactivating rather than deleting is the usual operation: a category with orders behind it
     * still has to be nameable when somebody looks at last year's invoices.
     */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public boolean isTopLevel() {
        return parentId == null;
    }

    /**
     * Turns a name into a slug.
     *
     * <p>Used when creating a category from a name alone. Deliberately conservative: anything that
     * is not a letter or a digit becomes a hyphen, and runs collapse. A slug that survives being
     * put in a URL without escaping is worth more than one that preserves every character.
     */
    public static String slugify(String value) {
        if (value == null) {
            return null;
        }
        String slug = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? null : slug;
    }
}
