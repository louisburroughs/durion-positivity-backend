package com.positivity.accounting.internal.billintake.service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * What a file is, by its first bytes (magic bytes): the allow-list of an upload is PDF, JPEG, PNG, HEIC and TIFF
 * (CSV and XLSX are imports only). The declared content type and the file name are never trusted; the worker's
 * inspection confirms the type later.
 */
public final class BillFileTypes {

    /** The types an upload may be. */
    public enum Type {
        PDF("application/pdf", ExtractionPort.DeclaredType.PDF),
        JPEG("image/jpeg", ExtractionPort.DeclaredType.JPEG),
        PNG("image/png", ExtractionPort.DeclaredType.PNG),
        HEIC("image/heic", ExtractionPort.DeclaredType.HEIC),
        TIFF("image/tiff", ExtractionPort.DeclaredType.TIFF);

        private final String contentType;
        private final ExtractionPort.DeclaredType declaredType;

        Type(String contentType, ExtractionPort.DeclaredType declaredType) {
            this.contentType = contentType;
            this.declaredType = declaredType;
        }

        public @NonNull String contentType() {
            return contentType;
        }

        public ExtractionPort.@NonNull DeclaredType declaredType() {
            return declaredType;
        }
    }

    /** ISO base media brands of HEIC and HEIF stills. */
    private static final Set<String> HEIC_BRANDS = Set.of("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1");

    private BillFileTypes() {}

    /** The type of {@code bytes} by its magic bytes, empty when it is none of the allowed types. */
    public static @NonNull Optional<Type> detect(byte @NonNull [] bytes) {
        if (startsWith(bytes, 0, '%', 'P', 'D', 'F', '-')) {
            return Optional.of(Type.PDF);
        }
        if (startsWith(bytes, 0, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(Type.JPEG);
        }
        if (startsWith(bytes, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(Type.PNG);
        }
        if (startsWith(bytes, 0, 'I', 'I', 0x2A, 0x00) || startsWith(bytes, 0, 'M', 'M', 0x00, 0x2A)) {
            return Optional.of(Type.TIFF);
        }
        if (bytes.length >= 12 && startsWith(bytes, 4, 'f', 't', 'y', 'p')) {
            String brand = new String(bytes, 8, 4, StandardCharsets.ISO_8859_1);
            if (HEIC_BRANDS.contains(brand)) {
                return Optional.of(Type.HEIC);
            }
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
