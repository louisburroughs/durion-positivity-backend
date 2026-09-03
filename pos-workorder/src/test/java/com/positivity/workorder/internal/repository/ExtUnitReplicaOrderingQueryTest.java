package com.positivity.workorder.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.workorder.internal.entity.ExtBayReplica;
import com.positivity.workorder.internal.entity.ExtMobileUnitReplica;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

/**
 * The dispatch board's unit panels are ordered by the query, not by the caller, so this pins that
 * order against a real database (Copilot review of #1656: {@code ORDER BY name} alone is not a
 * total order).
 *
 * <p>Two facts make the defect reachable rather than theoretical. A replica row that arrived only
 * as an assignment reference carries a null name until the location domain's event lands, and
 * nothing stops two units at one site from sharing a name. Either one leaves rows tied, and a tie
 * is resolved by whatever the database felt like returning — the panel then reshuffles between
 * refreshes with no underlying change. The rows below are inserted in an order that contradicts
 * the expected one, so a repository that fell back to insertion or primary-key order would fail
 * rather than accidentally pass.
 */
@DataJpaTest(properties = {"spring.flyway.enabled=false"})
class ExtUnitReplicaOrderingQueryTest {

    private static final UUID LOCATION = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID OTHER_LOCATION = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    // Deliberately allocated so that id order and expected display order disagree: the duplicate
    // "Bay 1" pair is ...d2 then ...d1, and the unnamed row sorts last while holding the LOWEST id.
    private static final UUID UNNAMED = UUID.fromString("00000000-0000-0000-0000-0000000000d0");
    private static final UUID DUP_LOW_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID DUP_HIGH_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID LAST_BY_NAME = UUID.fromString("00000000-0000-0000-0000-0000000000d3");

    private static final Instant SEEDED_AT = Instant.parse("2026-03-01T00:00:00Z");

    @Autowired
    private ExtBayReplicaRepository bayRepository;

    @Autowired
    private ExtMobileUnitReplicaRepository mobileUnitRepository;

    @Test
    @DisplayName("bays: duplicate names break by id, a null name sorts last, inactive and other sites drop out")
    void bayOrderIsTotal() {
        saveBay(DUP_HIGH_ID, LOCATION, "Bay 1", true);
        saveBay(LAST_BY_NAME, LOCATION, "Bay 2", true);
        saveBay(UNNAMED, LOCATION, null, true);
        saveBay(DUP_LOW_ID, LOCATION, "Bay 1", true);
        saveBay(UUID.fromString("00000000-0000-0000-0000-0000000000d8"), LOCATION, "Bay 0", false);
        saveBay(UUID.fromString("00000000-0000-0000-0000-0000000000d9"), OTHER_LOCATION, "Bay 0", true);

        assertThat(bayRepository.findActiveByLocationOrdered(LOCATION))
                .extracting(ExtBayReplica::getBayId)
                .containsExactly(DUP_LOW_ID, DUP_HIGH_ID, LAST_BY_NAME, UNNAMED);
    }

    @Test
    @DisplayName("bays: the order is identical on a second read, so the panel cannot jitter")
    void bayOrderRepeats() {
        saveBay(DUP_HIGH_ID, LOCATION, "Bay 1", true);
        saveBay(DUP_LOW_ID, LOCATION, "Bay 1", true);
        saveBay(UNNAMED, LOCATION, null, true);

        List<UUID> first = ids(bayRepository.findActiveByLocationOrdered(LOCATION));
        List<UUID> second = ids(bayRepository.findActiveByLocationOrdered(LOCATION));

        assertThat(first).isEqualTo(second).containsExactly(DUP_LOW_ID, DUP_HIGH_ID, UNNAMED);
    }

    @Test
    @DisplayName("mobile units: duplicate names break by id, a null name sorts last, other base sites drop out")
    void mobileUnitOrderIsTotal() {
        saveMobileUnit(DUP_HIGH_ID, LOCATION, "Van 1", true);
        saveMobileUnit(LAST_BY_NAME, LOCATION, "Van 2", true);
        saveMobileUnit(UNNAMED, LOCATION, null, true);
        saveMobileUnit(DUP_LOW_ID, LOCATION, "Van 1", true);
        saveMobileUnit(UUID.fromString("00000000-0000-0000-0000-0000000000d8"), LOCATION, "Van 0", false);
        saveMobileUnit(UUID.fromString("00000000-0000-0000-0000-0000000000d9"), OTHER_LOCATION, "Van 0", true);

        assertThat(mobileUnitRepository.findActiveByBaseLocationOrdered(LOCATION))
                .extracting(ExtMobileUnitReplica::getMobileUnitId)
                .containsExactly(DUP_LOW_ID, DUP_HIGH_ID, LAST_BY_NAME, UNNAMED);
    }

    private static List<UUID> ids(List<ExtBayReplica> bays) {
        return bays.stream().map(ExtBayReplica::getBayId).toList();
    }

    private void saveBay(UUID bayId, UUID locationId, String name, boolean active) {
        bayRepository.saveAndFlush(ExtBayReplica.builder()
                .bayId(bayId)
                .locationId(locationId)
                .name(name)
                .active(active)
                .aggregateVersion(1L)
                .updatedAt(SEEDED_AT)
                .build());
    }

    private void saveMobileUnit(UUID mobileUnitId, UUID baseLocationId, String name, boolean active) {
        mobileUnitRepository.saveAndFlush(ExtMobileUnitReplica.builder()
                .mobileUnitId(mobileUnitId)
                .baseLocationId(baseLocationId)
                .name(name)
                .active(active)
                .aggregateVersion(1L)
                .updatedAt(SEEDED_AT)
                .build());
    }
}
