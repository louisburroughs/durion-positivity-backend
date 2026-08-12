package com.positivity.supplier.internal.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.adapter.test_family.TestFamilyCodec;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Registry semantics per ADR-0051 §3: triple-keyed resolution, typed
 * {@code CAPABILITY_NOT_CONFIGURED} outcome for unbound triples (ADR-0050 §3), and startup
 * failure on duplicate registration.
 */
class AdapterRegistryTest {

    @Test
    void registeredTripleResolvesToTheRegisteredCodec() {
        TestFamilyCodec codec = new TestFamilyCodec();
        AdapterRegistry registry = new AdapterRegistry(List.of(codec));

        AdapterResolution resolution =
                registry.resolve(SupplierCapability.STOCK_INQUIRY, ProtocolFamily.TEST, TestFamilyCodec.TEST_V1);

        assertThat(resolution)
                .isInstanceOfSatisfying(
                        AdapterResolution.Resolved.class,
                        resolved -> assertThat(resolved.codec()).isSameAs(codec));
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void unboundTripleResolvesToNotConfiguredNeverNullNeverException() {
        AdapterRegistry registry = new AdapterRegistry(List.of(new TestFamilyCodec()));

        AdapterResolution resolution =
                registry.resolve(SupplierCapability.ORDER_CREATE, ProtocolFamily.TEST, TestFamilyCodec.TEST_V1);

        assertThat(resolution)
                .isInstanceOfSatisfying(
                        AdapterResolution.NotConfigured.class,
                        notConfigured -> assertThat(notConfigured.reason())
                                .contains("ORDER_CREATE")
                                .contains("TEST")
                                .contains("TEST_1"));
    }

    @Test
    void distinctVersionsOfOneCapabilityAndFamilyCoexist() {
        TestFamilyCodec v1 = new TestFamilyCodec(
                SupplierCapability.ORDER_STATUS, ProtocolFamily.TEST, new ProtocolVersion("TEST_1"));
        TestFamilyCodec v2 = new TestFamilyCodec(
                SupplierCapability.ORDER_STATUS, ProtocolFamily.TEST, new ProtocolVersion("TEST_2"));
        AdapterRegistry registry = new AdapterRegistry(List.of(v1, v2));

        AdapterResolution resolvedV2 =
                registry.resolve(SupplierCapability.ORDER_STATUS, ProtocolFamily.TEST, new ProtocolVersion("TEST_2"));

        assertThat(resolvedV2)
                .isInstanceOfSatisfying(
                        AdapterResolution.Resolved.class,
                        resolved -> assertThat(resolved.codec()).isSameAs(v2));
    }

    @Test
    void emptyRegistryResolvesEveryTripleToNotConfigured() {
        // Production reality of the foundation slice: no production codec exists yet, so the
        // registry must be constructible from zero codecs and answer every lookup with the
        // typed NotConfigured outcome (ADR-0050 §3) — not null, not an exception.
        AdapterRegistry registry = new AdapterRegistry(List.of());

        AdapterResolution resolution =
                registry.resolve(SupplierCapability.STOCK_INQUIRY, ProtocolFamily.EDIWHEEL_A25, ProtocolVersion.A2_5);

        assertThat(registry.size()).isZero();
        assertThat(resolution)
                .isInstanceOfSatisfying(
                        AdapterResolution.NotConfigured.class,
                        notConfigured -> assertThat(notConfigured.reason())
                                .contains("STOCK_INQUIRY")
                                .contains("EDIWHEEL_A25")
                                .contains("A2_5"));
    }

    @Test
    void resolutionRecordsEnforceTheirOwnInvariants() {
        // The sealed type is the module's typed-outcome contract: a NotConfigured without a
        // diagnostic or a Resolved without a codec would be meaningless values.
        assertThatThrownBy(() -> new AdapterResolution.NotConfigured("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason must not be blank");
        assertThatThrownBy(() -> new AdapterResolution.Resolved(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("codec");
    }

    @Test
    void duplicateTripleRegistrationFailsConstruction() {
        TestFamilyCodec first = new TestFamilyCodec();
        TestFamilyCodec second = new TestFamilyCodec();

        assertThatThrownBy(() -> new AdapterRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate supplier adapter codec registration")
                .hasMessageContaining("STOCK_INQUIRY")
                .hasMessageContaining("TEST_1")
                .hasMessageContaining(TestFamilyCodec.class.getName());
    }
}
