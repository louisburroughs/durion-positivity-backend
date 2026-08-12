package com.positivity.supplier.internal.adapter.test_family;

import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.spi.SupplierAdapterCodec;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/**
 * Test-only codec of the {@link ProtocolFamily#TEST} family, exercising the adapter-registry
 * registration and resolution semantics (ADR-0051 §3) without any production adapter. The
 * triple is constructor-configurable so tests can register distinct and colliding keys.
 */
public final class TestFamilyCodec implements SupplierAdapterCodec {

    public static final ProtocolVersion TEST_V1 = new ProtocolVersion("TEST_1");

    private final SupplierCapability capability;
    private final ProtocolFamily family;
    private final ProtocolVersion version;

    public TestFamilyCodec(
            @NonNull SupplierCapability capability, @NonNull ProtocolFamily family, @NonNull ProtocolVersion version) {
        this.capability = Objects.requireNonNull(capability, "capability must not be null");
        this.family = Objects.requireNonNull(family, "family must not be null");
        this.version = Objects.requireNonNull(version, "version must not be null");
    }

    /** A codec registered under {@code (STOCK_INQUIRY, TEST, TEST_1)}. */
    public TestFamilyCodec() {
        this(SupplierCapability.STOCK_INQUIRY, ProtocolFamily.TEST, TEST_V1);
    }

    @Override
    @NonNull
    public SupplierCapability capability() {
        return capability;
    }

    @Override
    @NonNull
    public ProtocolFamily family() {
        return family;
    }

    @Override
    @NonNull
    public ProtocolVersion version() {
        return version;
    }
}
