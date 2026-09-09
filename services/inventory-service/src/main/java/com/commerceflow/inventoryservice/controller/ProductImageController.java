package com.commerceflow.inventoryservice.controller;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.commerceflow.common.dto.ApiResponse;
import com.commerceflow.inventoryservice.dto.ProductImageResponse;
import com.commerceflow.inventoryservice.entity.ProductImage;
import com.commerceflow.inventoryservice.service.ProductImageService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Product photographs.
 *
 * <p>Reads are public, like the rest of the catalogue. Writes are administrator-only.
 *
 * <p>The bytes live in Postgres, which is a decision with stated limits — see
 * {@link ProductImage}. Every image served costs this service a request that a CDN would have
 * absorbed, which is why the response is cached as aggressively as it is: an image id is immutable,
 * so a browser that has one never needs to ask for it again.
 */
@RestController
@RequestMapping("/api/products/{productId}/images")
@RequiredArgsConstructor
@Tag(name = "Products", description = "Catalogue and stock")
public class ProductImageController {

    /**
     * A year, and marked immutable.
     *
     * <p>Safe because an image's bytes never change: editing means uploading a new image, which
     * gets a new id and therefore a new URL. Without that guarantee this would have to be a short
     * cache and every product page would re-fetch its pictures.
     */
    private static final CacheControl FOREVER =
            CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

    private final ProductImageService imageService;

    @GetMapping
    @Operation(summary = "A product's gallery",
            description = "Metadata and URLs, without the bytes. The image at position 0 is the "
                    + "one shown on a listing tile.")
    public ResponseEntity<ApiResponse<List<ProductImageResponse>>> list(
            @PathVariable UUID productId) {

        return ResponseEntity.ok(ApiResponse.ok(imageService.list(productId)));
    }

    /**
     * Serves one image.
     *
     * <p>Returns raw bytes rather than the usual {@code ApiResponse} envelope — an {@code <img>}
     * tag cannot unwrap JSON. This is the one endpoint in the service that is not an API call so
     * much as a file.
     */
    @GetMapping("/{imageId}")
    @Operation(summary = "Fetch an image",
            description = """
                    The bytes, with the content type determined when the file was uploaded — from \
                    the file itself, never from what the upload claimed.

                    Cached for a year and marked immutable, which is safe because an id's bytes \
                    never change: editing means a new image with a new id. A conditional request \
                    with the matching `If-None-Match` gets a 304.""")
    public ResponseEntity<byte[]> get(
            @PathVariable UUID productId,
            @PathVariable UUID imageId,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = "If-None-Match", required = false) String ifNoneMatch) {

        ProductImage image = imageService.load(productId, imageId);
        String etag = image.etag();

        if (etag.equals(ifNoneMatch)) {
            // The browser already has it. Sending the bytes again would be the single largest
            // avoidable cost in this service.
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(FOREVER)
                    .build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.getContentType()))
                .eTag(etag)
                .cacheControl(FOREVER)
                // Defence in depth. The upload was verified to be a real JPEG, PNG or WebP, so
                // this should never matter — but a browser that ignores a declared type and
                // sniffs its own is exactly how a "harmless" upload becomes script on our origin.
                .header("X-Content-Type-Options", "nosniff")
                .body(image.getData());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Upload an image",
            description = """
                    JPEG, PNG or WebP, identified from the bytes rather than from the \
                    `Content-Type` the client sent — believing that header is how an HTML \
                    document gets served back from this origin as an image.

                    **SVG is refused.** It is a document format that can contain script, and \
                    serving one inline from our own origin has the same consequence. Rasterising \
                    uploads or serving them from a separate origin would make it safe; neither is \
                    built.

                    The first image uploaded becomes the tile automatically, so a product never \
                    has a gallery and no thumbnail.""")
    public ResponseEntity<ApiResponse<ProductImageResponse>> upload(
            @PathVariable UUID productId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "altText", required = false) String altText) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(imageService.upload(productId, file, altText),
                        "Image uploaded"));
    }

    @PostMapping("/{imageId}/primary")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Make this the tile image",
            description = "Renumbers the whole gallery so positions stay contiguous.")
    public ResponseEntity<ApiResponse<List<ProductImageResponse>>> makePrimary(
            @PathVariable UUID productId, @PathVariable UUID imageId) {

        return ResponseEntity.ok(ApiResponse.ok(imageService.makePrimary(productId, imageId),
                "Tile image updated"));
    }

    @DeleteMapping("/{imageId}")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Delete an image",
            description = "Orders that were placed while it was the tile are unaffected — an "
                    + "order line copied the image it showed rather than pointing at this one.")
    public ResponseEntity<Void> delete(@PathVariable UUID productId, @PathVariable UUID imageId) {
        imageService.delete(productId, imageId);
        return ResponseEntity.noContent().build();
    }
}
