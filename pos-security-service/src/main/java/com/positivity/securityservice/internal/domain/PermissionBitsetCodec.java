package com.positivity.securityservice.internal.domain;

import com.positivity.securityservice.internal.enums.PermissionCode;

import org.jspecify.annotations.NonNull;

import java.util.Base64;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.Set;

/**
 * Pure utility class for encoding and decoding permission sets as compact
 * Base64URL bitsets.
 * <p>
 * Encoding uses Java's {@link BitSet} with little-endian bit ordering within
 * bytes,
 * serialized as Base64URL without padding. This is consistent with
 * {@link BitSet#toByteArray()} and {@link BitSet#valueOf(byte[])}.
 * </p>
 * <p>
 * No Spring dependencies, no I/O. All methods are stateless and thread-safe.
 * </p>
 */
public final class PermissionBitsetCodec {

    private PermissionBitsetCodec() {
        // utility class — not instantiable
    }

    /**
     * Encodes a set of {@link PermissionCode} values into a Base64URL string
     * without padding.
     * <p>
     * An empty set encodes to {@code ""} (empty string).
     * </p>
     *
     * @param permissions the set of permissions to encode; must not be null
     * @return Base64URL-encoded representation of the permission bitset, never
     *         padded
     */
    public static String encode(@NonNull Set<PermissionCode> permissions) {
        BitSet bitSet = new BitSet();
        for (PermissionCode permission : permissions) {
            bitSet.set(permission.bitIndex());
        }
        byte[] bytes = bitSet.toByteArray();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Decodes a Base64URL string (with or without padding) into a {@link BitSet}.
     * <p>
     * An empty or blank input string returns an empty {@link BitSet}.
     * </p>
     *
     * @param encoded the Base64URL-encoded bitset string; may be null or empty
     * @return the decoded {@link BitSet}, never null
     * @throws IllegalArgumentException if the input is non-empty but not valid
     *                                  Base64URL
     */
    public static BitSet decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return new BitSet();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            return BitSet.valueOf(bytes);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed Base64URL bitset: " + e.getMessage(), e);
        }
    }

    /**
     * Decodes a Base64URL bitset and maps set bits to {@link PermissionCode}
     * entries.
     * <p>
     * Bits at positions not mapped in the current catalog are silently skipped.
     * An empty or blank input returns an empty set.
     * </p>
     *
     * @param encoded the Base64URL-encoded bitset string
     * @param permVer the catalog version from the token (reserved for future
     *                version gating)
     * @return the set of resolved {@link PermissionCode} values; never null
     */
    public static Set<PermissionCode> decodeToPermissions(String encoded, int permVer) {
        BitSet bitSet = decode(encoded);
        Set<PermissionCode> result = EnumSet.noneOf(PermissionCode.class);
        for (PermissionCode permission : PermissionCode.values()) {
            if (bitSet.get(permission.bitIndex())) {
                result.add(permission);
            }
        }
        return result;
    }

    /**
     * Returns {@code true} if the encoded bitset grants the specified permission.
     *
     * @param encoded    the Base64URL-encoded bitset string
     * @param permission the permission to check; must not be null
     * @return {@code true} if the permission bit is set in the decoded bitset
     */
    public static boolean hasPermission(@NonNull String encoded, @NonNull PermissionCode permission) {
        return decode(encoded).get(permission.bitIndex());
    }
}
