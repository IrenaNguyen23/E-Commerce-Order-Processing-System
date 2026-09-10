package com.commerceflow.inventoryservice.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A photograph of a product.
 *
 * <h2>The bytes are in Postgres, and that is a decision with a shelf life</h2>
 *
 * <p>Object storage is where product images belong at any real volume: a CDN in front of S3 serves
 * them from an edge, never touches the application, and costs almost nothing. None of that
 * infrastructure has been chosen for this deployment yet, and the alternative to storing them here
 * was leaving image upload unbuilt.
 *
 * <p>So this is a working implementation with stated limits, rather than a stub:
 *
 * <ul>
 *   <li><b>Every byte flows through the application.</b> A page of twelve products is twelve
 *       requests the service has to serve, where a CDN would serve them for free.
 *   <li><b>Backups get large.</b> Image bytes are in the same dump as the catalogue.
 *   <li><b>There is no resizing.</b> A phone photograph is served at full size to a thumbnail.
 * </ul>
 *
 * <p>{@code ImageStore} is the seam. Moving to S3 means one new implementation of it and a
 * migration that copies rows out; nothing else in the service knows where bytes live.
 *
 * <h2>Why the bytes are lazy</h2>
 *
 * <p>{@link #data} is {@code FetchType.LAZY}. Listing a product's images to render a gallery needs
 * ids, sizes and alt text — not several megabytes of JPEG. Without this, every list query would
 * pull every image into memory to display their filenames.
 */
@Entity
@Table(name = "product_images", indexes = {
        @Index(name = "idx_product_images_product", columnList = "product_id, position")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductImage {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /**
     * The bytes.
     *
     * <p>Never returned by a listing endpoint; only by the one that serves a single image.
     */
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "data", nullable = false)
    private byte[] data;

    /**
     * The type, as determined by looking at the bytes rather than by believing the upload.
     *
     * <p>A client can put anything in a {@code Content-Type} header. Serving a file back under a
     * type nobody verified is how an HTML document ends up being rendered from your own origin.
     */
    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** The original file name, kept for the back office. Never used to build a URL. */
    @Column(name = "file_name", length = 255)
    private String fileName;

    /**
     * What a screen reader says.
     *
     * <p>Nullable, because an empty alt attribute is correct for a purely decorative image and
     * forcing text produces "image1.jpg" read aloud.
     */
    @Column(name = "alt_text", length = 255)
    private String altText;

    /** Gallery order. The lowest is the one shown on a listing tile. */
    @Column(name = "position", nullable = false)
    @Builder.Default
    private int position = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * A strong ETag, so a browser that already has this image is told so with a 304.
     *
     * <p>The id is enough on its own: an image's bytes never change — editing means uploading a
     * new one — so the id identifies the content exactly.
     */
    public String etag() {
        return "\"" + id + "\"";
    }
}
