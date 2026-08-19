package com.positivity.domainevents.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A fleet authorization request must name a vehicle (#1346).
 *
 * <p>The rule mirrors CAP-323's canonical request on the pos-supplier side: refusing at
 * construction is what turns a missing vehicle identity into a request problem visible before
 * anything is sent, rather than an opaque refusal from the vendor arriving later that looks like the
 * fleet declining to pay.
 */
@DisplayName("WorkorderFleetAuthRequestedV1 — a vehicle must be identifiable (#1346)")
class WorkorderFleetAuthRequestedV1Test {

    private static final UUID WORKORDER_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000c1");

    @Test
    @DisplayName("no identifier at all is refused")
    void noIdentifierIsRefused() {
        assertThatThrownBy(() -> new WorkorderFleetAuthRequestedV1(
                        WORKORDER_ID, "michelin-de", null, null, null, null, null, 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identify a vehicle");
    }

    @Test
    @DisplayName("blank identifiers count as absent")
    void blankIdentifiersAreRefused() {
        assertThatThrownBy(() -> new WorkorderFleetAuthRequestedV1(
                        WORKORDER_ID, "michelin-de", "  ", "", null, "   ", null, 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("any single identifier is enough")
    void oneIdentifierIsEnough() {
        assertThat(new WorkorderFleetAuthRequestedV1(
                                WORKORDER_ID, "michelin-de", null, "ABC-123", null, null, null, 1, List.of())
                        .licensePlate())
                .isEqualTo("ABC-123");
        assertThat(new WorkorderFleetAuthRequestedV1(
                                WORKORDER_ID, "michelin-de", null, null, "1HGCM82633A004352", null, null, 1, List.of())
                        .vin())
                .isEqualTo("1HGCM82633A004352");
        assertThat(new WorkorderFleetAuthRequestedV1(
                                WORKORDER_ID, "michelin-de", null, null, null, "FLEET-9", null, 1, List.of())
                        .fleetNumber())
                .isEqualTo("FLEET-9");
        assertThat(new WorkorderFleetAuthRequestedV1(
                                WORKORDER_ID, "michelin-de", "VENDOR-1", null, null, null, null, 1, List.of())
                        .vendorVehicleId())
                .isEqualTo("VENDOR-1");
    }

    @Test
    @DisplayName("attempt must be at least 1")
    void attemptMustBePositive() {
        assertThatThrownBy(() -> new WorkorderFleetAuthRequestedV1(
                        WORKORDER_ID, "michelin-de", "VENDOR-1", null, null, null, null, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempt");
    }

    @Test
    @DisplayName("a negative line quantity is refused")
    void negativeQuantityIsRefused() {
        assertThatThrownBy(() -> new WorkorderFleetAuthRequestedV1.RequestedLine("EAN-1", "Tyres", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
