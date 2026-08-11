package com.positivity.supplier.internal.spi;

import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import org.jspecify.annotations.NonNull;

/**
 * Registration unit of the adapter registry (ADR-0051 §3): every protocol-family codec
 * implements this interface and is discovered as a Spring bean by
 * {@code com.positivity.supplier.internal.registry.AdapterRegistry}, keyed by the immutable
 * triple {@code (capability, family, version)}.
 *
 * <p>Ports express capability semantics; codecs are how one wire-format family realizes a
 * capability in one norm version. A new norm version is a new codec class in the existing
 * family, registered under the new version key (ADR-0051 §4). Codecs perform no I/O — output
 * is bytes + content-type handed to the shared base client (ADR-0051 §5).
 */
public interface SupplierAdapterCodec {

    /** The business capability this codec realizes. */
    @NonNull SupplierCapability capability();

    /** The wire-format family this codec belongs to (one adapter package per family, ADR-0051 §2). */
    @NonNull ProtocolFamily family();

    /** The norm version this codec speaks. */
    @NonNull ProtocolVersion version();
}
