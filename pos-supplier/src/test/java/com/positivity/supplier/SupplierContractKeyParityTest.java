package com.positivity.supplier;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.service.model.AuthConfigView;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
        Set<String> enumNames = Arrays.stream(com.positivity.supplier.internal.domain.model.SupplierCapability.values())
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
     * The {@code service.model} enums that mirror an internal enum of the same name. ADR-0026
     * forbids an internal type crossing the service boundary, so the contract carries its own
     * copy — and nothing in the type system keeps the two in step. The admin service converts
     * between them with {@code valueOf(name())}, so a constant added on one side only becomes an
     * {@code IllegalArgumentException} at runtime. This pins them instead.
     */
    static Stream<Arguments> mirroredEnums() {
        return Stream.of(
                Arguments.of(
                        com.positivity.supplier.service.model.PayloadCaptureLevel.class,
                        com.positivity.supplier.internal.enums.PayloadCaptureLevel.class),
                Arguments.of(
                        com.positivity.supplier.service.model.RetryBackoff.class,
                        com.positivity.supplier.internal.enums.RetryBackoff.class),
                Arguments.of(
                        com.positivity.supplier.service.model.SupplierAuthType.class,
                        com.positivity.supplier.internal.enums.SupplierAuthType.class),
                Arguments.of(
                        com.positivity.supplier.service.model.SupplierAccountRole.class,
                        com.positivity.supplier.internal.enums.SupplierAccountRole.class),
                Arguments.of(
                        com.positivity.supplier.service.model.ProfileSourceOfTruth.class,
                        com.positivity.supplier.internal.enums.ProfileSourceOfTruth.class),
                Arguments.of(
                        com.positivity.supplier.service.model.AuditAccessKind.class,
                        com.positivity.supplier.internal.enums.AuditAccessKind.class),
                Arguments.of(
                        com.positivity.supplier.service.model.AuditPayloadOutcome.class,
                        com.positivity.supplier.internal.enums.AuditPayloadOutcome.class));
    }

    /**
     * The two audit enums additionally have a <em>third</em> copy: the {@code chk_saccess_kind} and
     * {@code chk_saccess_payload_outcome} CHECK constraints in V4. A constant added to the enums but not
     * to the constraint is not a compile error and not a mapping error — it is a runtime constraint
     * violation on an audit write, which is the worst place to discover it, because ADR-0050 §7 makes
     * that write a precondition of serving a payload. This pins the enums to the migration text.
     */
    @Test
    void auditEnumsMatchTheCheckConstraintsInTheV4Migration() throws Exception {
        String migration = new String(
                java.nio.file.Files.readAllBytes(
                        java.nio.file.Path.of("src/main/resources/db/migration/V4__supplier_audit_access.sql")),
                java.nio.charset.StandardCharsets.UTF_8);

        for (var kind : com.positivity.supplier.internal.enums.AuditAccessKind.values()) {
            assertThat(constraintBody(migration, "chk_saccess_kind"))
                    .as("AuditAccessKind.%s must be permitted by chk_saccess_kind", kind)
                    .contains("'" + kind.name() + "'");
        }
        for (var outcome : com.positivity.supplier.internal.enums.AuditPayloadOutcome.values()) {
            assertThat(constraintBody(migration, "chk_saccess_payload_outcome"))
                    .as("AuditPayloadOutcome.%s must be permitted by chk_saccess_payload_outcome", outcome)
                    .contains("'" + outcome.name() + "'");
        }
    }

    /** The text of one named CHECK constraint, so a constant cannot match a value in an unrelated one. */
    private static String constraintBody(String migration, String constraintName) {
        int start = migration.indexOf("CONSTRAINT " + constraintName);
        assertThat(start)
                .as("V4 must still declare %s; a renamed constraint silently voids this pin", constraintName)
                .isNotNegative();
        int end = migration.indexOf("))", start);
        assertThat(end).as("%s must be a parenthesised CHECK", constraintName).isNotNegative();
        return migration.substring(start, end);
    }

    @ParameterizedTest(name = "{0} mirrors {1} constant for constant")
    @MethodSource("mirroredEnums")
    void contractEnumMirrorsItsInternalEnumExactly(
            Class<? extends Enum<?>> contractEnum, Class<? extends Enum<?>> internalEnum) {
        Set<String> contractNames =
                Arrays.stream(contractEnum.getEnumConstants()).map(Enum::name).collect(Collectors.toSet());
        Set<String> internalNames =
                Arrays.stream(internalEnum.getEnumConstants()).map(Enum::name).collect(Collectors.toSet());

        assertThat(contractNames)
                .as(
                        "%s and %s are converted with valueOf(name()); a constant on one side only is a"
                                + " runtime IllegalArgumentException, so they must match exactly",
                        contractEnum, internalEnum)
                .containsExactlyInAnyOrderElementsOf(internalNames);
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
