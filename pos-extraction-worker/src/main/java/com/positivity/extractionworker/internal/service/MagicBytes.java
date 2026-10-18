package com.positivity.extractionworker.internal.service;

import com.positivity.extractionworker.internal.enums.DeclaredType;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Identifies the binary types from a file's first bytes, in the web process, before any child is
 * started. This is not parsing: it reads sixteen bytes. CSV has no magic number and is checked in the
 * child.
 */
final class MagicBytes {

    static final int HEAD_BYTES = 16;

    private static final Set<String> HEIC_BRANDS =
            Set.of("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1");

    private MagicBytes() {}

    /** The binary type the bytes show, or null when they match none of them. */
    static @Nullable DeclaredType detect(byte @NonNull [] head, int length) {
        if (startsWith(head, length, 0x25, 0x50, 0x44, 0x46, 0x2D)) {
            return DeclaredType.PDF;
        }
        if (startsWith(head, length, 0xFF, 0xD8, 0xFF)) {
            return DeclaredType.JPEG;
        }
        if (startsWith(head, length, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return DeclaredType.PNG;
        }
        if (startsWith(head, length, 0x49, 0x49, 0x2A, 0x00) || startsWith(head, length, 0x4D, 0x4D, 0x00, 0x2A)) {
            return DeclaredType.TIFF;
        }
        if (startsWith(head, length, 0x50, 0x4B, 0x03, 0x04)) {
            return DeclaredType.XLSX;
        }
        if (length >= 12
                && head[4] == 'f'
                && head[5] == 't'
                && head[6] == 'y'
                && head[7] == 'p'
                && HEIC_BRANDS.contains(new String(head, 8, 4, StandardCharsets.US_ASCII))) {
            return DeclaredType.HEIC;
        }
        return null;
    }

    private static boolean startsWith(byte[] head, int length, int... expected) {
        if (length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((head[i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
