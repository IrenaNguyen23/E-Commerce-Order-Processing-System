package com.commerceflow.inventoryservice.service;

import java.util.Arrays;

/**
 * What kind of image a byte array actually is.
 *
 * <h2>Read the bytes, not the header</h2>
 *
 * <p>A multipart upload arrives with a {@code Content-Type} the client chose. Believing it and
 * storing the file under that type means an attacker can upload an HTML document as
 * {@code image/png}; the service then serves it back, from its own origin, with a type that makes
 * a browser render it. Every cookie and every token scoped to that origin is then reachable from
 * script inside a file somebody uploaded.
 *
 * <p>So the type is determined here, from the first few bytes, and an upload whose real format is
 * not on this list is refused — not stored under a corrected type. A file that is not one of these
 * three things has no business in a product gallery whatever it turns out to be.
 *
 * <h2>SVG is deliberately absent</h2>
 *
 * <p>SVG is a document format that happens to draw pictures. It can contain {@code <script>}, and
 * serving one inline from the application's own origin is the same problem as above with extra
 * steps. Rasterising uploads or serving them from a separate origin would make it safe; neither is
 * built, so SVG uploads are refused.
 *
 * <p>(The demo catalogue's inline SVG placeholders are unaffected: those are {@code data:} URIs in
 * a product's {@code imageUrl} column, authored here rather than uploaded, and a {@code data:} URI
 * carries no origin.)
 */
enum ImageFormat {

    JPEG("image/jpeg", new int[] {0xFF, 0xD8, 0xFF}),

    PNG("image/png", new int[] {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}),

    /** RIFF container; the "WEBP" marker at offset 8 is checked separately. */
    WEBP("image/webp", new int[] {0x52, 0x49, 0x46, 0x46});

    private final String contentType;
    private final int[] magic;

    ImageFormat(String contentType, int[] magic) {
        this.contentType = contentType;
        this.magic = magic;
    }

    String contentType() {
        return contentType;
    }

    /**
     * Identifies an upload, or {@code null} when it is not an image this service will store.
     *
     * <p>Null rather than an exception: the caller has a better message to give than this class
     * does, and it is the caller that knows whether an unrecognised file is an error or simply
     * something to skip.
     */
    static ImageFormat detect(byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }
        for (ImageFormat format : values()) {
            if (format.matches(data)) {
                return format;
            }
        }
        return null;
    }

    private boolean matches(byte[] data) {
        for (int i = 0; i < magic.length; i++) {
            if ((data[i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        if (this != WEBP) {
            return true;
        }
        // RIFF is a container that also holds WAV and AVI. Without this second check, an audio
        // file would be stored and served as an image.
        byte[] marker = Arrays.copyOfRange(data, 8, 12);
        return marker[0] == 'W' && marker[1] == 'E' && marker[2] == 'B' && marker[3] == 'P';
    }
}
