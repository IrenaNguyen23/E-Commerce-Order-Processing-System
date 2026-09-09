package com.commerceflow.inventoryservice.service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.inventoryservice.config.InventoryProperties;
import com.commerceflow.inventoryservice.dto.ProductImageResponse;
import com.commerceflow.inventoryservice.entity.Product;
import com.commerceflow.inventoryservice.entity.ProductImage;
import com.commerceflow.inventoryservice.repository.ProductImageRepository;
import com.commerceflow.inventoryservice.repository.ProductRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Product photographs.
 *
 * <h2>What is checked, and in what order</h2>
 *
 * <ol>
 *   <li><b>Size</b>, before anything is read into a decision — a rejected 50 MB upload should be
 *       rejected on its size, not after being inspected.
 *   <li><b>The actual format</b>, from the bytes. The declared {@code Content-Type} is never
 *       trusted or stored; see {@link ImageFormat} for what believing it costs.
 *   <li><b>How many the product already has.</b> A cap, because this is unbounded storage attached
 *       to an authenticated endpoint.
 * </ol>
 *
 * <h2>The first image becomes the tile</h2>
 *
 * <p>Position 0 is what a listing shows. The first upload takes it automatically rather than
 * leaving a product with a gallery and no thumbnail — the same reasoning as the first saved
 * address becoming the default.
 *
 * <h2>Images are immutable</h2>
 *
 * <p>There is no "replace the bytes of image X". Editing means uploading a new image and deleting
 * the old one, which is what makes the id a safe cache key: a URL that has been served can never
 * come back with different content, so it can be cached for a year without a version parameter.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImageService {

    private final ProductImageRepository images;
    private final ProductRepository products;
    private final InventoryProperties properties;

    /** The gallery, without the bytes. */
    @Transactional(readOnly = true)
    public List<ProductImageResponse> list(UUID productId) {
        requireProduct(productId);
        return images.findByProductIdOrderByPositionAscCreatedAtAsc(productId).stream()
                .map(image -> toResponse(productId, image))
                .toList();
    }

    /**
     * One image, bytes included.
     *
     * <p>Scoped by product as well as by id. Looking it up by id alone would let anyone who
     * guessed an id fetch it under any product's URL, which is harmless here and is exactly the
     * habit that is not harmless somewhere else.
     */
    @Transactional(readOnly = true)
    public ProductImage load(UUID productId, UUID imageId) {
        return images.findByIdAndProductId(imageId, productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Image not found: " + imageId));
    }

    /**
     * Stores an upload.
     *
     * @throws BusinessException when the file is empty, too large, not really an image, or the
     *     product already has as many as it is allowed
     */
    @Transactional
    @CacheEvict(cacheNames = {ProductService.CACHE_BY_ID, ProductService.CACHE_BY_SKU},
            allEntries = true)
    public ProductImageResponse upload(UUID productId, MultipartFile file, String altText) {
        Product product = requireProduct(productId);

        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "No file was uploaded");
        }

        long maxBytes = properties.getMaxImageBytes();
        if (file.getSize() > maxBytes) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "That image is " + (file.getSize() / 1024) + " kB. The limit is "
                            + (maxBytes / 1024) + " kB.");
        }

        long existing = images.countByProductId(productId);
        if (existing >= properties.getMaxImagesPerProduct()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    product.getSku() + " already has " + existing
                            + " images. Delete one before adding another.");
        }

        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "That upload could not be read");
        }

        // The bytes decide, not the header the client sent. See ImageFormat.
        ImageFormat format = ImageFormat.detect(data);
        if (format == null) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "That file is not a JPEG, PNG or WebP image. SVG is not accepted.");
        }

        ProductImage image = images.save(ProductImage.builder()
                .id(UUID.randomUUID())
                .productId(productId)
                .data(data)
                .contentType(format.contentType())
                .sizeBytes(data.length)
                .fileName(sanitise(file.getOriginalFilename()))
                .altText(trimToNull(altText))
                // The first one is the tile, so a product never has a gallery and no thumbnail.
                .position((int) existing)
                .createdAt(Instant.now())
                .build());

        log.info("Stored {} image for product {} ({} kB)", format.contentType(), product.getSku(),
                data.length / 1024);
        return toResponse(productId, image);
    }

    /**
     * Promotes an image to the tile position.
     *
     * <p>Renumbers the whole gallery rather than swapping two rows, so positions stay contiguous.
     * Gaps are harmless until somebody writes a query that assumes they are not.
     */
    @Transactional
    @CacheEvict(cacheNames = {ProductService.CACHE_BY_ID, ProductService.CACHE_BY_SKU},
            allEntries = true)
    public List<ProductImageResponse> makePrimary(UUID productId, UUID imageId) {
        ProductImage promoted = load(productId, imageId);
        List<ProductImage> gallery =
                images.findByProductIdOrderByPositionAscCreatedAtAsc(productId);

        int position = 1;
        for (ProductImage image : gallery) {
            image.setPosition(image.getId().equals(promoted.getId()) ? 0 : position++);
        }
        images.saveAll(gallery);

        return list(productId);
    }

    /** Removes an image and closes the gap it left in the ordering. */
    @Transactional
    @CacheEvict(cacheNames = {ProductService.CACHE_BY_ID, ProductService.CACHE_BY_SKU},
            allEntries = true)
    public void delete(UUID productId, UUID imageId) {
        ProductImage image = load(productId, imageId);
        images.delete(image);
        images.flush();

        List<ProductImage> remaining =
                images.findByProductIdOrderByPositionAscCreatedAtAsc(productId);
        int position = 0;
        for (ProductImage other : remaining) {
            other.setPosition(position++);
        }
        images.saveAll(remaining);

        log.info("Deleted image {} from product {}", imageId, productId);
    }

    /**
     * The URL of a product's tile image, or {@code null} when it has no uploads.
     *
     * <p>Used to fill {@code ProductResponse.imageUrl}, so a client that knows nothing about
     * galleries still gets a picture. A product with no uploaded images keeps whatever URL was set
     * on it directly — which is how the seeded catalogue's inline placeholders survive.
     */
    @Transactional(readOnly = true)
    public String primaryUrl(UUID productId) {
        return images.findFirstByProductIdOrderByPositionAscCreatedAtAsc(productId)
                .map(image -> url(productId, image.getId()))
                .orElse(null);
    }

    static String url(UUID productId, UUID imageId) {
        return "/api/products/" + productId + "/images/" + imageId;
    }

    private Product requireProduct(UUID productId) {
        return products.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + productId));
    }

    private static ProductImageResponse toResponse(UUID productId, ProductImage image) {
        return new ProductImageResponse(image.getId(), url(productId, image.getId()),
                image.getContentType(), image.getSizeBytes(), image.getAltText(),
                image.getPosition(), image.getPosition() == 0, image.getCreatedAt());
    }

    /**
     * Keeps a file name for the back office without letting it near a path.
     *
     * <p>The name is display-only — URLs are built from ids — but a stored {@code ../../etc/passwd}
     * is a trap for whatever code later decides to use it for something.
     */
    private static String sanitise(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String name = fileName.replaceAll("[\\\\/]", "_").trim();
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
