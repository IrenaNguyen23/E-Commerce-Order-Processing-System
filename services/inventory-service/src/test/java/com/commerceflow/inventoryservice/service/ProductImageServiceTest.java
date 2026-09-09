package com.commerceflow.inventoryservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.inventoryservice.config.InventoryProperties;
import com.commerceflow.inventoryservice.dto.ProductImageResponse;
import com.commerceflow.inventoryservice.entity.Product;
import com.commerceflow.inventoryservice.entity.ProductImage;
import com.commerceflow.inventoryservice.repository.ProductImageRepository;
import com.commerceflow.inventoryservice.repository.ProductRepository;

/**
 * Product image upload.
 *
 * <p>The tests worth having here are about what is <em>not</em> stored. An upload arrives with a
 * content type the client chose; believing it means an attacker can put an HTML document in the
 * catalogue and have this service serve it back, from its own origin, as something a browser will
 * render. Everything scoped to that origin is then reachable from script inside a file somebody
 * uploaded.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductImageServiceTest {

    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @Mock
    private ProductImageRepository images;

    @Mock
    private ProductRepository products;

    private ProductImageService service;

    @BeforeEach
    void setUp() {
        service = new ProductImageService(images, products, new InventoryProperties());

        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(Product.builder()
                .id(PRODUCT_ID).sku("CF-LAPTOP-001").name("Laptop").build()));
        when(images.save(any(ProductImage.class))).thenAnswer(call -> call.getArgument(0));
        when(images.countByProductId(PRODUCT_ID)).thenReturn(0L);
    }

    // ---- fixtures ---------------------------------------------------------------------------

    /** A minimal but genuine PNG header, followed by filler to clear the length check. */
    private static byte[] png() {
        byte[] data = new byte[64];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, data, 0, magic.length);
        return data;
    }

    private static byte[] jpeg() {
        byte[] data = new byte[64];
        data[0] = (byte) 0xFF;
        data[1] = (byte) 0xD8;
        data[2] = (byte) 0xFF;
        return data;
    }

    private static byte[] webp() {
        byte[] data = new byte[64];
        System.arraycopy("RIFF".getBytes(), 0, data, 0, 4);
        System.arraycopy("WEBP".getBytes(), 0, data, 8, 4);
        return data;
    }

    private static MockMultipartFile file(String declaredType, byte[] data) {
        return new MockMultipartFile("file", "photo.png", declaredType, data);
    }

    // =====================================================================================

    @Nested
    @DisplayName("What gets accepted")
    class Accepted {

        @Test
        @DisplayName("a real PNG, JPEG or WebP")
        void realImagesAreStored() {
            assertThat(service.upload(PRODUCT_ID, file("image/png", png()), null).contentType())
                    .isEqualTo("image/png");
            assertThat(service.upload(PRODUCT_ID, file("image/jpeg", jpeg()), null).contentType())
                    .isEqualTo("image/jpeg");
            assertThat(service.upload(PRODUCT_ID, file("image/webp", webp()), null).contentType())
                    .isEqualTo("image/webp");
        }

        @Test
        @DisplayName("the stored type comes from the bytes, not from what the upload claimed")
        void declaredTypeIsIgnored() {
            // Sent as image/gif; it is a PNG. The bytes win, and nothing anywhere records the
            // client's claim — a stored type nobody verified is a type that gets served back.
            ProductImageResponse stored =
                    service.upload(PRODUCT_ID, file("image/gif", png()), null);

            assertThat(stored.contentType()).isEqualTo("image/png");
        }

        @Test
        @DisplayName("the first upload becomes the tile")
        void firstUploadIsTheTile() {
            // Otherwise a product ends up with a gallery and no thumbnail, and a listing page
            // shows a gap where a picture obviously belongs.
            assertThat(service.upload(PRODUCT_ID, file("image/png", png()), null).primary())
                    .isTrue();
        }

        @Test
        @DisplayName("a later upload does not steal the tile")
        void laterUploadIsNotTheTile() {
            when(images.countByProductId(PRODUCT_ID)).thenReturn(2L);

            assertThat(service.upload(PRODUCT_ID, file("image/png", png()), null).primary())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("What gets refused")
    class Refused {

        @Test
        @DisplayName("an HTML document dressed as a PNG")
        void htmlIsRefused() {
            byte[] html = "<html><script>alert(document.cookie)</script></html>".getBytes();

            // The whole reason ImageFormat exists. Stored and served back under the type the
            // client asked for, this is script running on this service's own origin.
            assertThatThrownBy(() -> service.upload(PRODUCT_ID, file("image/png", html), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not a JPEG, PNG or WebP");
            verify(images, never()).save(any());
        }

        @Test
        @DisplayName("SVG, even a valid one")
        void svgIsRefused() {
            byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'><script/></svg>".getBytes();

            // SVG is a document format that draws pictures. Refused rather than sanitised,
            // because sanitising untrusted markup is a losing game and nothing here needs to.
            assertThatThrownBy(() -> service.upload(PRODUCT_ID, file("image/svg+xml", svg), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("SVG is not accepted");
        }

        @Test
        @DisplayName("a RIFF file that is not actually WebP")
        void riffAudioIsRefused() {
            // RIFF also holds WAV and AVI. Checking only the container would store an audio file
            // and serve it as image/webp.
            byte[] wav = new byte[64];
            System.arraycopy("RIFF".getBytes(), 0, wav, 0, 4);
            System.arraycopy("WAVE".getBytes(), 0, wav, 8, 4);

            assertThatThrownBy(() -> service.upload(PRODUCT_ID, file("image/webp", wav), null))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("a file over the size limit, before it is even inspected")
        void oversizedIsRefused() {
            InventoryProperties tight = new InventoryProperties();
            tight.setMaxImageBytes(1024);
            ProductImageService strict = new ProductImageService(images, products, tight);

            byte[] big = new byte[4096];
            System.arraycopy(png(), 0, big, 0, 8);

            assertThatThrownBy(() -> strict.upload(PRODUCT_ID, file("image/png", big), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("limit is 1 kB");
        }

        @Test
        @DisplayName("an upload past the per-product cap")
        void galleryIsCapped() {
            when(images.countByProductId(PRODUCT_ID)).thenReturn(8L);

            assertThatThrownBy(() -> service.upload(PRODUCT_ID, file("image/png", png()), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already has 8");
        }

        @Test
        @DisplayName("an empty file")
        void emptyFileIsRefused() {
            assertThatThrownBy(() ->
                    service.upload(PRODUCT_ID, file("image/png", new byte[0]), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("No file");
        }

        @Test
        @DisplayName("a truncated file that only looks like a header")
        void tooShortIsRefused() {
            // Four bytes of PNG magic and nothing else. Length is checked before the magic so a
            // two-byte upload cannot index past the end of the array.
            assertThatThrownBy(() ->
                    service.upload(PRODUCT_ID, file("image/png", new byte[] {(byte) 0x89, 'P'}),
                            null))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("Managing a gallery")
    class Gallery {

        @Test
        @DisplayName("promoting an image renumbers the rest contiguously")
        void promotionRenumbers() {
            ProductImage first = image(0);
            ProductImage second = image(1);
            ProductImage third = image(2);
            when(images.findByIdAndProductId(third.getId(), PRODUCT_ID))
                    .thenReturn(Optional.of(third));
            when(images.findByProductIdOrderByPositionAscCreatedAtAsc(PRODUCT_ID))
                    .thenReturn(List.of(first, second, third));

            service.makePrimary(PRODUCT_ID, third.getId());

            // Renumbered rather than swapped, so positions stay 0,1,2. Gaps are harmless right up
            // until something is written that assumes they are not.
            assertThat(third.getPosition()).isZero();
            assertThat(first.getPosition()).isEqualTo(1);
            assertThat(second.getPosition()).isEqualTo(2);
        }

        @Test
        @DisplayName("deleting closes the gap it left")
        void deletionRenumbers() {
            ProductImage going = image(0);
            ProductImage remaining = image(2);
            when(images.findByIdAndProductId(going.getId(), PRODUCT_ID))
                    .thenReturn(Optional.of(going));
            when(images.findByProductIdOrderByPositionAscCreatedAtAsc(PRODUCT_ID))
                    .thenReturn(List.of(remaining));

            service.delete(PRODUCT_ID, going.getId());

            // And the survivor becomes the tile, so deleting the primary image does not leave a
            // product with pictures and no thumbnail.
            assertThat(remaining.getPosition()).isZero();
        }

        @Test
        @DisplayName("an image is looked up under its own product, not by id alone")
        void lookupIsScopedByProduct() {
            UUID otherProductsImage = UUID.randomUUID();
            when(images.findByIdAndProductId(otherProductsImage, PRODUCT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.load(PRODUCT_ID, otherProductsImage))
                    .hasMessageContaining("Image not found");
        }

        @Test
        @DisplayName("the etag is the id, because bytes behind an id never change")
        void etagIsTheId() {
            ProductImage image = image(0);

            // What makes a one-year immutable cache honest: editing means a new image with a new
            // id and a new URL, so a served URL can never come back different.
            assertThat(image.etag()).isEqualTo("\"" + image.getId() + "\"");
        }

        private ProductImage image(int position) {
            return ProductImage.builder()
                    .id(UUID.randomUUID()).productId(PRODUCT_ID).data(png())
                    .contentType("image/png").sizeBytes(64).position(position).build();
        }
    }

    @Test
    @DisplayName("a path in the file name is defused rather than stored as one")
    void fileNameIsSanitised() {
        MockMultipartFile traversal =
                new MockMultipartFile("file", "../../etc/passwd", "image/png", png());

        service.upload(PRODUCT_ID, traversal, null);

        // The name is display-only — URLs are built from ids — but a stored path is a trap for
        // whatever code later decides to use it for something.
        verify(images).save(org.mockito.ArgumentMatchers.argThat(
                image -> !image.getFileName().contains("/")));
    }
}
