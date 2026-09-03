package com.positivity.shopmanager.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtMobileUnitReplica;
import com.positivity.shopmanager.internal.entity.ExtWorkorderReplica;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dashboard's roster and workorder order is decided by the query, so this pins it against a
 * real database (Copilot review of #1656/#1658: an {@code ORDER BY name} that is not a total order
 * lets rows reshuffle between refreshes).
 *
 * <p>Ties are reachable, not theoretical: a replica row that arrived only as an assignment
 * reference has a null name until the owning domain's event lands, and nothing stops two units at
 * one site from sharing a name. For {@code findOpenAtLocation} the stakes are higher than jitter —
 * its sort feeds a 200-row cap, so a tie at the boundary changes <em>which</em> rows come back, not
 * merely their order. Rows are inserted in an order that contradicts the expected one so that a
 * query falling back to insertion or key order fails instead of accidentally passing.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExtReplicaOrderingQueryTest {

    private static final UUID LOCATION = UUID.fromString("01960012-0000-7000-8000-0000000000c1");
    private static final UUID OTHER_LOCATION = UUID.fromString("01960012-0000-7000-8000-0000000000c2");

    // Allocated so id order and expected display order disagree: the duplicate-name pair is
    // inserted high-id first, and the unnamed row sorts last while holding the lowest id.
    private static final UUID UNNAMED = UUID.fromString("01960012-0000-7000-8000-0000000000d0");
    private static final UUID DUP_LOW_ID = UUID.fromString("01960012-0000-7000-8000-0000000000d1");
    private static final UUID DUP_HIGH_ID = UUID.fromString("01960012-0000-7000-8000-0000000000d2");
    private static final UUID LAST_BY_NAME = UUID.fromString("01960012-0000-7000-8000-0000000000d3");
    private static final UUID INACTIVE = UUID.fromString("01960012-0000-7000-8000-0000000000d8");
    private static final UUID ELSEWHERE = UUID.fromString("01960012-0000-7000-8000-0000000000d9");

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "CANCELLED");
    private static final Instant SEEDED_AT = Instant.parse("2026-03-01T00:00:00Z");

    @Autowired
    private ExtBayReplicaRepository bayRepository;

    @Autowired
    private ExtMobileUnitReplicaRepository mobileUnitRepository;

    @Autowired
    private ExtWorkorderReplicaRepository workorderRepository;

    @Test
    @DisplayName("bays: duplicate names break by id, a null name sorts last, inactive and other sites drop out")
    void bayOrderIsTotal() {
        saveBay(DUP_HIGH_ID, LOCATION, "Bay 1", true);
        saveBay(LAST_BY_NAME, LOCATION, "Bay 2", true);
        saveBay(UNNAMED, LOCATION, null, true);
        saveBay(DUP_LOW_ID, LOCATION, "Bay 1", true);
        saveBay(INACTIVE, LOCATION, "Bay 0", false);
        saveBay(ELSEWHERE, OTHER_LOCATION, "Bay 0", true);

        assertThat(bayRepository.findActiveByLocationOrdered(LOCATION))
                .extracting(ExtBayReplica::getBayId)
                .containsExactly(DUP_LOW_ID, DUP_HIGH_ID, LAST_BY_NAME, UNNAMED);
    }

    @Test
    @DisplayName("bays: a second read returns the identical order, so the roster cannot jitter")
    void bayOrderRepeats() {
        saveBay(DUP_HIGH_ID, LOCATION, "Bay 1", true);
        saveBay(DUP_LOW_ID, LOCATION, "Bay 1", true);
        saveBay(UNNAMED, LOCATION, null, true);

        List<UUID> first = bayRepository.findActiveByLocationOrdered(LOCATION).stream()
                .map(ExtBayReplica::getBayId)
                .toList();
        List<UUID> second = bayRepository.findActiveByLocationOrdered(LOCATION).stream()
                .map(ExtBayReplica::getBayId)
                .toList();

        assertThat(first).isEqualTo(second).containsExactly(DUP_LOW_ID, DUP_HIGH_ID, UNNAMED);
    }

    @Test
    @DisplayName("mobile units: duplicate names break by id, a null name sorts last, other base sites drop out")
    void mobileUnitOrderIsTotal() {
        saveMobileUnit(DUP_HIGH_ID, LOCATION, "Van 1", true);
        saveMobileUnit(LAST_BY_NAME, LOCATION, "Van 2", true);
        saveMobileUnit(UNNAMED, LOCATION, null, true);
        saveMobileUnit(DUP_LOW_ID, LOCATION, "Van 1", true);
        saveMobileUnit(INACTIVE, LOCATION, "Van 0", false);
        saveMobileUnit(ELSEWHERE, OTHER_LOCATION, "Van 0", true);

        assertThat(mobileUnitRepository.findActiveByBaseLocationOrdered(LOCATION))
                .extracting(ExtMobileUnitReplica::getMobileUnitId)
                .containsExactly(DUP_LOW_ID, DUP_HIGH_ID, LAST_BY_NAME, UNNAMED);
    }

    @Test
    @DisplayName("open workorders: rows identical down to a null number still order by id, so the row cap is stable")
    void openWorkordersOrderIsTotal() {
        // Same tier, same (absent) promise, same (absent) number: without the id tier these three
        // tie completely, and under the cap a tie changes which rows are returned at all.
        saveWorkorder(LAST_BY_NAME, "WO-2", null);
        saveWorkorder(DUP_HIGH_ID, null, null);
        saveWorkorder(DUP_LOW_ID, null, null);
        saveWorkorder(UNNAMED, "WO-1", null);

        assertThat(findOpen(10))
                .extracting(ExtWorkorderReplica::getWorkorderId)
                .containsExactly(UNNAMED, LAST_BY_NAME, DUP_LOW_ID, DUP_HIGH_ID);
    }

    @Test
    @DisplayName("open workorders: the row cap keeps the same subset across refreshes")
    void openWorkorderCapReturnsTheSameSubset() {
        saveWorkorder(LAST_BY_NAME, "WO-2", null);
        saveWorkorder(DUP_HIGH_ID, null, null);
        saveWorkorder(DUP_LOW_ID, null, null);
        saveWorkorder(UNNAMED, "WO-1", null);

        List<UUID> first = capped();
        List<UUID> second = capped();

        assertThat(first).isEqualTo(second).containsExactly(UNNAMED, LAST_BY_NAME);
    }

    private List<UUID> capped() {
        return findOpen(2).stream().map(ExtWorkorderReplica::getWorkorderId).toList();
    }

    private List<ExtWorkorderReplica> findOpen(int limit) {
        return workorderRepository.findOpenAtLocation(LOCATION, TERMINAL, PageRequest.of(0, limit));
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

    private void saveWorkorder(UUID workorderId, String workorderNumber, Instant promisedAt) {
        workorderRepository.saveAndFlush(ExtWorkorderReplica.builder()
                .workorderId(workorderId)
                .workorderNumber(workorderNumber)
                .status("ASSIGNED")
                .locationId(LOCATION)
                .promisedAt(promisedAt)
                .aggregateVersion(1L)
                .updatedAt(SEEDED_AT)
                .build());
    }
}
