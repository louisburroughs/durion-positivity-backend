package com.positivity.supplier.internal.client;

import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpMethod;

/**
 * Turning a codec's transport-free request spec into a request the client can send (#1349).
 *
 * <h2>Why this is one place</h2>
 *
 * The mapping is a nine-argument copy from one record to another, and it was written out eight
 * times. That is exactly the shape that goes wrong quietly: a field added to
 * {@link SupplierRequestSpec} reaches only the call sites somebody remembered, and the ones it
 * misses keep compiling. Per-request headers were added for CAP-323 and had to be threaded through
 * every copy by hand; the next field will too, unless there is one copy.
 */
public final class SupplierRequests {

    private SupplierRequests() {}

    /** Maps a codec's spec onto the binding it will be sent through. */
    @NonNull
    public static SupplierHttpRequest toHttpRequest(
            @NonNull ResolvedBinding binding, @NonNull SupplierRequestSpec spec) {
        return new SupplierHttpRequest(
                binding,
                HttpMethod.valueOf(spec.method()),
                spec.pathSuffix(),
                spec.queryParams(),
                spec.body(),
                spec.contentType(),
                spec.accept(),
                spec.idempotent(),
                spec.headers());
    }
}
