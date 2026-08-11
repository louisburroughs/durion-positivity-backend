package com.positivity.supplier.internal.registry;

import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.spi.SupplierAdapterCodec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Adapter registry (ADR-0051 §3): collects every {@link SupplierAdapterCodec} bean at
 * construction, keyed by the triple {@code (capability, protocolFamily, version)}.
 *
 * <p>Duplicate registrations of one triple fail application startup with
 * {@link IllegalStateException} — two codecs claiming the same triple is a wiring bug, not a
 * runtime condition. An unbound triple resolves to {@link AdapterResolution.NotConfigured}
 * (the typed {@code CAPABILITY_NOT_CONFIGURED} outcome of ADR-0050 §3), never {@code null}
 * and never an exception.
 */
@Component
public class AdapterRegistry {

    private final Map<Key, SupplierAdapterCodec> codecsByKey;

    @Autowired
    public AdapterRegistry(@NonNull ObjectProvider<SupplierAdapterCodec> codecProvider) {
        this(codecProvider.orderedStream().toList());
    }

    /**
     * Builds the registry from an explicit codec list (deterministic registration order;
     * used directly by tests and by the Spring constructor above).
     *
     * @throws IllegalStateException when two codecs register the same
     *     {@code (capability, family, version)} triple
     */
    public AdapterRegistry(@NonNull List<SupplierAdapterCodec> codecs) {
        Objects.requireNonNull(codecs, "codecs must not be null");
        Map<Key, SupplierAdapterCodec> byKey = HashMap.newHashMap(codecs.size());
        for (SupplierAdapterCodec codec : codecs) {
            Key key = new Key(codec.capability(), codec.family(), codec.version());
            SupplierAdapterCodec previous = byKey.putIfAbsent(key, codec);
            if (previous != null) {
                throw new IllegalStateException("Duplicate supplier adapter codec registration for "
                        + key.describe() + ": " + previous.getClass().getName() + " and "
                        + codec.getClass().getName());
            }
        }
        this.codecsByKey = Map.copyOf(byKey);
    }

    /**
     * Resolves the codec registered for the given triple.
     *
     * @return {@link AdapterResolution.Resolved} when a codec is registered,
     *     {@link AdapterResolution.NotConfigured} otherwise — never {@code null}, never an
     *     exception for the unbound case
     */
    @NonNull public AdapterResolution resolve(
            @NonNull SupplierCapability capability, @NonNull ProtocolFamily family, @NonNull ProtocolVersion version) {
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Key key = new Key(capability, family, version);
        SupplierAdapterCodec codec = codecsByKey.get(key);
        if (codec == null) {
            return new AdapterResolution.NotConfigured("No supplier adapter codec registered for " + key.describe());
        }
        return new AdapterResolution.Resolved(codec);
    }

    /** Number of registered codecs. */
    public int size() {
        return codecsByKey.size();
    }

    private record Key(SupplierCapability capability, ProtocolFamily family, ProtocolVersion version) {

        private String describe() {
            return "(" + capability + ", " + family + ", " + version.value() + ")";
        }
    }
}
