package com.positivity.supplier.internal.registry;

import com.positivity.supplier.internal.spi.SupplierAdapterCodec;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/**
 * Typed outcome of an adapter-registry lookup (ADR-0050 §3, ADR-0051 §3): an unbound
 * {@code (capability, family, version)} triple is a legitimate configuration state
 * ({@code CAPABILITY_NOT_CONFIGURED}), never a {@code null} and never an exception.
 */
public sealed interface AdapterResolution permits AdapterResolution.Resolved, AdapterResolution.NotConfigured {

    /**
     * A codec is registered for the requested triple.
     *
     * @param codec the registered codec
     */
    record Resolved(@NonNull SupplierAdapterCodec codec) implements AdapterResolution {
        public Resolved {
            Objects.requireNonNull(codec, "codec must not be null");
        }
    }

    /**
     * No codec is registered for the requested triple — the capability is not configured for
     * this deployment/binding.
     *
     * @param reason human-readable diagnostic naming the unbound triple; never blank
     */
    record NotConfigured(@NonNull String reason) implements AdapterResolution {
        public NotConfigured {
            Objects.requireNonNull(reason, "reason must not be null");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }
    }
}
