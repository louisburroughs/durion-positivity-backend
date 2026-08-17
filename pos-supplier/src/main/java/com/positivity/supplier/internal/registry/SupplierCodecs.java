package com.positivity.supplier.internal.registry;

import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import com.positivity.supplier.internal.spi.SupplierAdapterCodec;
import org.jspecify.annotations.NonNull;

/**
 * Resolving the codec bound to a capability (#1349).
 *
 * <h2>Why this is one place</h2>
 *
 * Every capability needs the same three steps — ask the registry for
 * {@code (capability, family, version)}, check the answer is the codec type this capability speaks,
 * and raise a configuration error naming the triple when it is not. That was written out ten times,
 * once per caller, and the copies had already drifted: some raised
 * {@code SupplierConfigurationException}, some {@code IllegalStateException}, so the same
 * misconfiguration surfaced as a 409 in one capability and a 500 in another.
 *
 * <p>A deployment defect should look the same wherever it is found. Hence one implementation.
 */
public final class SupplierCodecs {

    private SupplierCodecs() {}

    /**
     * The codec bound to this capability, as the type the capability speaks.
     *
     * @param registry   the adapter registry
     * @param binding    the resolved endpoint binding, which names the family and version
     * @param capability the capability being resolved
     * @param codecType  the codec interface or class this capability requires
     * @throws SupplierConfigurationException when nothing is registered for the triple, or when what
     *     is registered is not the type this capability can use — both deployment defects, and both
     *     surfaced loudly rather than degraded into a vendor-looking failure
     */
    @NonNull
    public static <T extends SupplierAdapterCodec> T require(
            @NonNull AdapterRegistry registry,
            @NonNull ResolvedBinding binding,
            @NonNull SupplierCapability capability,
            @NonNull Class<T> codecType) {
        AdapterResolution resolution = registry.resolve(capability, binding.family(), binding.version());
        if (resolution instanceof AdapterResolution.Resolved resolved && codecType.isInstance(resolved.codec())) {
            return codecType.cast(resolved.codec());
        }
        throw new SupplierConfigurationException(
                SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED,
                "No " + capability + " codec registered for " + binding.family() + " "
                        + binding.version().value());
    }
}
