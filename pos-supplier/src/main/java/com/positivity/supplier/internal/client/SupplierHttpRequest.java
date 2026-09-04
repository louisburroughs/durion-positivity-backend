package com.positivity.supplier.internal.client;

import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;

/**
 * One logical outbound call, at the transport level. The base client is deliberately
 * format-agnostic: a protocol codec (CAP-318) produces {@code body} as an already-serialized wire
 * document and maps the response back to a canonical model, so nothing here knows about EDIWheel
 * XML or Michelin JSON.
 *
 * @param binding the resolved binding supplying base URL, path, timeouts and credentials
 * @param method HTTP method
 * @param pathSuffix appended to the binding's path when a capability addresses a sub-resource;
 *     {@code null} for none. Must begin with {@code /} when present
 * @param queryParams query parameters, in insertion order; never contains credentials
 * @param body already-serialized request body; {@code null} for bodiless methods
 * @param contentType content type of {@code body}; required when {@code body} is present
 * @param accept desired response media type; {@code null} to send no Accept header
 * @param idempotent whether this call may be retried after the body was transmitted. ADR-0052 §5
 *     permits free retry <em>only</em> for reads bounded by a checkpointed window (PRICAT, stock
 *     report, invoice fetch) whose consumers deduplicate by natural identity. It defaults to
 *     {@code false} through {@link #of}, because the cost of getting this wrong is a duplicate
 *     purchase order at a vendor
 */
public record SupplierHttpRequest(
        @NonNull ResolvedBinding binding,
        @NonNull HttpMethod method,
        @Nullable String pathSuffix,
        @NonNull Map<String, String> queryParams,
        @Nullable String body,
        @Nullable String contentType,
        @Nullable String accept,
        boolean idempotent,
        @NonNull Map<String, String> headers) {

    // Left as IllegalArgumentException (#1694): built exclusively by internal transport code from
    // an already-validated SupplierRequestSpec, never from client-supplied HTTP input. A violation
    // here is this module's own defect and belongs on the platform 500 fallback, not a client 4xx.
    public SupplierHttpRequest {
        Objects.requireNonNull(binding, "binding must not be null");
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(queryParams, "queryParams must not be null");
        if (pathSuffix != null && !pathSuffix.startsWith("/")) {
            throw new IllegalArgumentException("pathSuffix must start with '/' when present");
        }
        if (body != null && (contentType == null || contentType.isBlank())) {
            throw new IllegalArgumentException("contentType is required when a body is present");
        }
        Objects.requireNonNull(headers, "headers must not be null");
        // Collections.unmodifiableMap over a LinkedHashMap, not Map.copyOf: the latter gives no
        // ordering guarantee, and the query string is built by iterating this map. SupplierRequestSpec
        // takes the same care one layer up, so copying with Map.copyOf here discarded the order it had
        // just preserved -- leaving a vendor's query parameters in an arbitrary sequence that could
        // differ between runs of the same request.
        queryParams = Collections.unmodifiableMap(new LinkedHashMap<>(queryParams));
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    /** Builds a request that adds no headers of its own. */
    public SupplierHttpRequest(
            @NonNull ResolvedBinding binding,
            @NonNull HttpMethod method,
            @Nullable String pathSuffix,
            @NonNull Map<String, String> queryParams,
            @Nullable String body,
            @Nullable String contentType,
            @Nullable String accept,
            boolean idempotent) {
        this(binding, method, pathSuffix, queryParams, body, contentType, accept, idempotent, Map.of());
    }

    /**
     * A non-idempotent call with no query parameters — the safe default shape.
     *
     * @param binding resolved binding
     * @param method HTTP method
     * @param body serialized body, or {@code null}
     * @param contentType content type, required with a body
     * @return the request
     */
    @NonNull
    public static SupplierHttpRequest of(
            @NonNull ResolvedBinding binding,
            @NonNull HttpMethod method,
            @Nullable String body,
            @Nullable String contentType) {
        return new SupplierHttpRequest(binding, method, null, Map.of(), body, contentType, null, false);
    }

    /**
     * Marks this request as safe to retry after transmission (ADR-0052 §5 checkpointed reads).
     *
     * @return a copy with {@code idempotent = true}
     */
    @NonNull
    public SupplierHttpRequest asIdempotentRead() {
        return new SupplierHttpRequest(
                binding, method, pathSuffix, queryParams, body, contentType, accept, true, headers);
    }
}
