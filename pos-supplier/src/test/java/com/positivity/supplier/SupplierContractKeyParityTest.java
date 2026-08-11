package com.positivity.supplier;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.service.model.AuthConfigView;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins the contract-legal canonical string keys of the admin API
 * ({@code EndpointBindingRequest.capability} / {@code .protocolFamily}, ADR-0050 §3) to the
 * internal {@code SupplierCapability} / {@code ProtocolFamily} enum names (slice-1 review
 * debt). The contract deliberately carries strings, not enums (ADR-0026 — no internal type may
 * leak), so nothing in the type system stops the enums drifting away from the documented key
 * sets: this test does. Renaming/adding/removing an enum constant fails here first, forcing a
 * deliberate contract decision.
 *
 * <p>{@link ProtocolFamily#TEST} is registry-fixture-only and excluded from the contract-legal
 * set — the admin service rejects it with {@code SUPPLIER_UNKNOWN_PROTOCOL_FAMILY}.
 */
class SupplierContractKeyParityTest {

    /** The capability keys admins may bind — ADR-0050 §3 / contract javadoc. */
    private static final Set<String> CONTRACT_CAPABILITY_KEYS = Set.of(
            "ORDER_CREATE",
            "ORDER_STATUS",
            "STOCK_INQUIRY",
            "STOCK_REPORT",
            "PRICE_CATALOG",
            "INVOICE_FETCH",
            "SHIPMENT_TRACKING",
            "WORKORDER_AUTHORIZATION",
            "MARKETING_CATALOG",
            "TIRE_IDENTIFICATION");

    /** The protocol family keys admins may bind — ADR-0051 §2 / contract javadoc. */
    private static final Set<String> CONTRACT_PROTOCOL_FAMILY_KEYS =
            Set.of("EDIWHEEL_A25", "EDIWHEEL_C1", "EDIWHEEL_B", "EDIWHEEL_JSON", "MICHELIN_S2S");

    @Test
    void capabilityEnumNamesMatchTheContractKeySetExactly() {
        Set<String> enumNames = Arrays.stream(
                        com.positivity.supplier.internal.domain.model.SupplierCapability.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertThat(enumNames)
                .as("SupplierCapability enum names ARE the contract capability keys — changing the enum"
                        + " changes the admin API contract and must update this pin deliberately")
                .containsExactlyInAnyOrderElementsOf(CONTRACT_CAPABILITY_KEYS);
    }

    @Test
    void protocolFamilyEnumNamesMatchTheContractKeySetExactlyWithTestExcluded() {
        Set<String> enumNames = Arrays.stream(ProtocolFamily.values())
                .map(Enum::name)
                .filter(name -> !"TEST".equals(name))
                .collect(Collectors.toSet());

        assertThat(enumNames)
                .as("ProtocolFamily enum names (minus the fixture-only TEST family) ARE the contract"
                        + " protocol family keys")
                .containsExactlyInAnyOrderElementsOf(CONTRACT_PROTOCOL_FAMILY_KEYS);
        assertThat(Arrays.stream(ProtocolFamily.values()).map(Enum::name))
                .as("the TEST family stays enum-only: present for registry fixtures, never contract-legal")
                .contains("TEST");
    }

    /**
     * Carry-forward from the slice-1 review: {@code AuthConfigView} must never gain secret
     * reference fields — references are write-only ({@code AuthConfigRequest}) by shape.
     */
    @Test
    void authConfigViewCarriesNoSecretReferenceComponents() {
        assertThat(Arrays.stream(AuthConfigView.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName))
                .as("AuthConfigView components must never include *Ref secret reference fields (ADR-0050 §4)")
                .noneMatch(name -> name.endsWith("Ref"))
                .containsExactlyInAnyOrder("authConfigId", "name", "type", "apiKeyHeader");
    }
}
