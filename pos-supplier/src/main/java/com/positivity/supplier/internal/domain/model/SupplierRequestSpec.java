package com.positivity.supplier.internal.domain.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What a codec decided one outbound call should look like, expressed without any transport type
 * (ADR-0051 §2/§5).
 *
 * <p>Codecs know the vendor norm; they do not know how this deployment makes HTTP calls. Keeping
 * the decision in a framework-free record is what lets the adapter package depend on
 * {@code internal.spi} and {@code internal.domain} alone: the orchestration layer turns this into a
 * transport request against the resolved binding, and no vendor knowledge leaks into transport, nor
 * transport into the codec.
 *
 * <p>{@code queryParams} never carries a credential. Credentials are applied by the auth strategy
 * on the resolved binding, after this record has done its job.
 *
 * @param method      HTTP method name, e.g. {@code GET}
 * @param pathSuffix  appended to the binding's path when the capability addresses a sub-resource;
 *                    null for none, and must start with {@code /} when present
 * @param queryParams query parameters in insertion order
 * @param body        already-serialized request body; null for bodiless methods
 * @param contentType content type of {@code body}; required when a body is present
 * @param accept      desired response media type; null to send no Accept header
 * @param idempotent  whether the call may be retried after transmission (ADR-0052 §5)
 */
public record SupplierRequestSpec(
        @NonNull String method,
        @Nullable String pathSuffix,
        @NonNull Map<String, String> queryParams,
        @Nullable String body,
        @Nullable String contentType,
        @Nullable String accept,
        boolean idempotent) {

    public SupplierRequestSpec {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(queryParams, "queryParams must not be null");
        if (method.isBlank()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        if (pathSuffix != null && !pathSuffix.startsWith("/")) {
            throw new IllegalArgumentException("pathSuffix must start with '/' when present");
        }
        if (body != null && (contentType == null || contentType.isBlank())) {
            throw new IllegalArgumentException("contentType is required when a body is present");
        }
        // Collections.unmodifiableMap over a LinkedHashMap, not Map.copyOf: the latter is unordered,
        // which would quietly make the documented insertion order untrue and produce a different
        // query string on every run.
        queryParams = Collections.unmodifiableMap(new LinkedHashMap<>(queryParams));
    }
}
