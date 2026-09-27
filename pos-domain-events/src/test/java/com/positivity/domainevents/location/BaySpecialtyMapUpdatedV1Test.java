package com.positivity.domainevents.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code BaySpecialtyMapUpdatedV1}'s invariants (DECISION-LOCATION-025, CAP-325 D14.1). */
class BaySpecialtyMapUpdatedV1Test {

    private static final UUID TENANT_ID = UUID.fromString("01990000-0000-7000-8000-0000000000f1");

    @Test
    @DisplayName("A null tenantId is rejected")
    void nullTenantIdRejected() {
        assertThatThrownBy(() -> new BaySpecialtyMapUpdatedV1(null, List.of(), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    @DisplayName("A null entries list is rejected")
    void nullEntriesRejected() {
        assertThatThrownBy(() -> new BaySpecialtyMapUpdatedV1(TENANT_ID, null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entries");
    }

    @Test
    @DisplayName("A negative aggregateVersion is rejected")
    void negativeVersionRejected() {
        assertThatThrownBy(() -> new BaySpecialtyMapUpdatedV1(TENANT_ID, List.of(), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregateVersion");
    }

    @Test
    @DisplayName("The entries list is defensively copied and immutable")
    void entriesAreDefensivelyCopied() {
        List<BaySpecialtyMapUpdatedV1.Entry> mutable = new ArrayList<>();
        mutable.add(new BaySpecialtyMapUpdatedV1.Entry("ALIGNMENT", List.of("WHEEL-ALIGNMENT-4-WHEEL"), true));

        BaySpecialtyMapUpdatedV1 fact = new BaySpecialtyMapUpdatedV1(TENANT_ID, mutable, 1);
        mutable.add(new BaySpecialtyMapUpdatedV1.Entry("TIRE_SERVICE", List.of("TIRE-INSTALL-SET-4"), true));

        assertThat(fact.entries()).hasSize(1);
        assertThatThrownBy(() -> fact.entries().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Entry: a blank bayType is rejected")
    void entryBlankBayTypeRejected() {
        assertThatThrownBy(() -> new BaySpecialtyMapUpdatedV1.Entry(" ", List.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bayType");
    }

    @Test
    @DisplayName("Entry: a null operationCodes list defaults to empty rather than throwing")
    void entryNullOperationCodesDefaultsToEmpty() {
        BaySpecialtyMapUpdatedV1.Entry entry = new BaySpecialtyMapUpdatedV1.Entry("GENERAL_SERVICE", null, true);

        assertThat(entry.operationCodes()).isEmpty();
    }

    @Test
    @DisplayName("WASH_DETAIL's shape: empty operationCodes, acceptsGeneralWork false")
    void washDetailShape() {
        BaySpecialtyMapUpdatedV1.Entry washDetail = new BaySpecialtyMapUpdatedV1.Entry("WASH_DETAIL", List.of(), false);

        assertThat(washDetail.operationCodes()).isEmpty();
        assertThat(washDetail.acceptsGeneralWork()).isFalse();
    }
}
