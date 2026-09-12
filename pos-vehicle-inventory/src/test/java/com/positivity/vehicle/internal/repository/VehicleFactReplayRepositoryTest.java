package com.positivity.vehicle.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.vehicle.PostgresSliceTestBase;
import com.positivity.vehicle.internal.entity.VehicleRecord;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * The vehicle fact replay ({@link VehicleRecordRepository#findForReplay}) against the real
 * PostgreSQL baseline.
 *
 * <h2>What this defends</h2>
 *
 * The replay was written as one JPQL string of {@code (:param IS NULL OR …)} clauses, which
 * PostgreSQL rejects at parse time with {@code could not determine data type of parameter $n} — the
 * {@code updatedSince} placeholder — so every call to the replay endpoint was a 500 while the
 * H2-backed tests of the same query passed (issue #1891). Running this against PostgreSQL is
 * therefore the whole point: on H2 it would pass against the defect too.
 *
 * <p>The replay is exercised the same five ways the specification's two independently optional
 * filters allow: unfiltered, by the cursor, by the timestamp, by both, and bounded by the page size.
 */
@DisplayName("Vehicle fact replay on PostgreSQL (#1891)")
class VehicleFactReplayRepositoryTest extends PostgresSliceTestBase {

    /** Later than anything the baseline seeds, so it selects exactly this test's fixtures. */
    private static final Instant FIXTURE_STAMP = Instant.parse("2030-01-01T00:00:00Z");

    private static final Instant AFTER_FIXTURES = Instant.parse("2030-06-01T00:00:00Z");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private VehicleRecordRepository vehicles;

    /**
     * Stamps a row's {@code updated_at} directly. Auditing owns the column on every save, so a test
     * that needs a specific instant has to write it behind the entity manager and then clear the
     * persistence context so the next read sees it.
     */
    private void stampUpdatedAt(UUID vehicleId, Instant updatedAt) {
        entityManager.flush();
        entityManager
                .createNativeQuery("UPDATE vehicle_records SET updated_at = ?1 WHERE vehicle_id = ?2")
                .setParameter(1, updatedAt)
                .setParameter(2, vehicleId)
                .executeUpdate();
        entityManager.clear();
    }

    /**
     * Three vehicles stamped at {@link #FIXTURE_STAMP}, returned in the ascending id order the
     * replay cursor depends on.
     */
    private List<UUID> threeStampedVehicles(String prefix) {
        List<UUID> ids = List.of(
                vehicles.saveAndFlush(vehicle(prefix + "1")).getVehicleId(),
                vehicles.saveAndFlush(vehicle(prefix + "2")).getVehicleId(),
                vehicles.saveAndFlush(vehicle(prefix + "3")).getVehicleId());
        ids.forEach(id -> stampUpdatedAt(id, FIXTURE_STAMP));
        return ids.stream().sorted().toList();
    }

    private static VehicleRecord vehicle(String suffix) {
        String vin = ("1FTFW1ET" + suffix + "00000000").substring(0, 17);
        return VehicleRecord.builder()
                .accountId(UUID.fromString("01900000-0000-7000-8000-0000000000a1"))
                .vin(vin)
                .vinNormalized(vin)
                .unitNumber("UNIT-" + suffix)
                .description("replay fixture " + suffix)
                .isActive(true)
                .build();
    }

    private static List<UUID> idsOf(List<VehicleRecord> records) {
        return records.stream().map(VehicleRecord::getVehicleId).toList();
    }

    @Test
    @DisplayName("an unfiltered replay reads the table rather than failing to parse")
    void unfilteredReplayReadsTheTable() {
        List<UUID> ids = threeStampedVehicles("A");

        assertThat(idsOf(vehicles.findForReplay(null, null, PageRequest.of(0, 500))))
                .contains(ids.get(0), ids.get(1), ids.get(2));
    }

    @Test
    @DisplayName("the updatedSince filter selects the rows changed at or after the instant")
    void updatedSinceSelectsChangedRows() {
        List<UUID> ids = threeStampedVehicles("B");

        assertThat(idsOf(vehicles.findForReplay(null, FIXTURE_STAMP, PageRequest.of(0, 50))))
                .containsExactly(ids.get(0), ids.get(1), ids.get(2));
        assertThat(vehicles.findForReplay(null, AFTER_FIXTURES, PageRequest.of(0, 50)))
                .as("a window past every fixture selects nothing, rather than failing to parse")
                .isEmpty();
    }

    @Test
    @DisplayName("the cursor is exclusive, so a resumed page never repeats a row")
    void cursorIsExclusive() {
        List<UUID> ids = threeStampedVehicles("C");

        assertThat(idsOf(vehicles.findForReplay(ids.get(0), FIXTURE_STAMP, PageRequest.of(0, 50))))
                .containsExactly(ids.get(1), ids.get(2));
    }

    @Test
    @DisplayName("the page size bounds the replay")
    void pageSizeBoundsTheReplay() {
        List<UUID> ids = threeStampedVehicles("D");

        assertThat(idsOf(vehicles.findForReplay(null, FIXTURE_STAMP, PageRequest.of(0, 2))))
                .containsExactly(ids.get(0), ids.get(1));
    }

    @Test
    @DisplayName("an unpaged replay returns every match rather than failing")
    void unpagedReplayReturnsEveryMatch() {
        List<UUID> ids = threeStampedVehicles("E");

        // Pageable.unpaged() reports a page size of zero, which PageRequest.of rejects; a search
        // that rebuilds the pageable has to carry the unpaged case through rather than throw.
        assertThat(idsOf(vehicles.findForReplay(null, FIXTURE_STAMP, Pageable.unpaged())))
                .containsExactly(ids.get(0), ids.get(1), ids.get(2));
    }
}
