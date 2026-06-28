package com.positivity.invoice.internal.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Identifies a single invoice artifact by type and underlying entity id.
 *
 * <p>Serialised to an opaque, URL-safe {@code artifactRefId} (base64url of {@code TYPE:uuid}) so
 * the frontend can carry it in paths without escaping concerns.
 */
public record ArtifactRef(@NonNull Type type, @NonNull UUID id) {

    public enum Type {
        INVOICE,
        RECEIPT
    }

    @NonNull
    public String encode() {
        String raw = type.name() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @NonNull
    public static ArtifactRef decode(@NonNull String artifactRefId) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(artifactRefId), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Malformed artifactRefId", ex);
        }
        int sep = raw.indexOf(':');
        if (sep <= 0) {
            throw new IllegalArgumentException("Malformed artifactRefId");
        }
        Type type;
        try {
            type = Type.valueOf(raw.substring(0, sep));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown artifact type in artifactRefId", ex);
        }
        UUID id;
        try {
            id = UUID.fromString(raw.substring(sep + 1));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Malformed artifact id in artifactRefId", ex);
        }
        return new ArtifactRef(type, id);
    }
}
