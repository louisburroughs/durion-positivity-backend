package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.location.internal.dto.LocationRef;
import com.positivity.location.internal.dto.LocationResponseDTO;
import com.positivity.location.internal.entity.BayEntity;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.MobileUnitEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence-backed tests for the repair-capability projection on
 * {@code GET /v1/locations} and {@code GET /v1/locations/roster} (issue #1657).
 *
 * <p>The projection is computed at query time from two aggregate queries, so it
 * only holds up against a real database: these tests drive the service against
 * H2 rather than mocking the repositories, and re-read after every mutation with
 * no cache or refresh step in between.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("LocationRepairCapabilityTest")
class LocationRepairCapabilityTest {

    private static final String BAY_STATUS_ACTIVE = "ACTIVE";
    private static final String BAY_STATUS_OUT_OF_SERVICE = "OUT_OF_SERVICE";
    private static final String UNIT_STATUS_ACTIVE = "ACTIVE";
    private static final String UNIT_STATUS_INACTIVE = "INACTIVE";

    @Autowired
    private LocationServiceImpl locationService;

    @Autowired
    private LocationRosterServiceImpl locationRosterService;

    @Autowired
    private EntityManager em;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    // -------------------------------------------------------------------------
    // AC3, AC4, AC11 — the capability matrix
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#1657 - a location with only active bays is repair-capable")
    void baysOnly_isRepairCapable() {
        Location location = persistLocation("Bays Only", true);
        persistBay(location, "Bay 1", BAY_STATUS_ACTIVE);
        persistBay(location, "Bay 2", BAY_STATUS_ACTIVE);
        flushAndClear();

        LocationResponseDTO dto = listAndFind(location.getId());

        assertThat(dto.isHasRepairCapability()).isTrue();
        assertThat(dto.getActiveBayCount()).isEqualTo(2);
        assertThat(dto.getActiveMobileUnitCount()).isZero();
    }

    @Test
    @DisplayName("#1657 - a location with only active mobile units is repair-capable")
    void mobileUnitsOnly_isRepairCapable() {
        Location location = persistLocation("Mobile Only", true);
        persistMobileUnit(location, "Van 1", UNIT_STATUS_ACTIVE);
        flushAndClear();

        LocationResponseDTO dto = listAndFind(location.getId());

        assertThat(dto.isHasRepairCapability()).isTrue();
        assertThat(dto.getActiveBayCount()).isZero();
        assertThat(dto.getActiveMobileUnitCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("#1657 - bays and mobile units are counted independently")
    void baysAndMobileUnits_countedIndependently() {
        Location location = persistLocation("Both", true);
        persistBay(location, "Bay 1", BAY_STATUS_ACTIVE);
        persistBay(location, "Bay 2", BAY_STATUS_ACTIVE);
        persistBay(location, "Bay 3", BAY_STATUS_ACTIVE);
        persistMobileUnit(location, "Van 1", UNIT_STATUS_ACTIVE);
        persistMobileUnit(location, "Van 2", UNIT_STATUS_ACTIVE);
        flushAndClear();

        LocationResponseDTO dto = listAndFind(location.getId());

        assertThat(dto.isHasRepairCapability()).isTrue();
        assertThat(dto.getActiveBayCount()).isEqualTo(3);
        assertThat(dto.getActiveMobileUnitCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("#1657 - a location with neither bays nor mobile units is not repair-capable")
    void neither_isNotRepairCapable() {
        Location location = persistLocation("Bare", true);
        flushAndClear();

        LocationResponseDTO dto = listAndFind(location.getId());

        assertThat(dto.isHasRepairCapability()).isFalse();
        assertThat(dto.getActiveBayCount()).isZero();
        assertThat(dto.getActiveMobileUnitCount()).isZero();
    }

    @Test
    @DisplayName("#1657 - OUT_OF_SERVICE bays and INACTIVE mobile units do not count")
    void nonOperationalRows_areExcluded() {
        Location location = persistLocation("Shuttered", true);
        persistBay(location, "Bay 1", BAY_STATUS_OUT_OF_SERVICE);
        persistBay(location, "Bay 2", BAY_STATUS_OUT_OF_SERVICE);
        persistMobileUnit(location, "Van 1", UNIT_STATUS_INACTIVE);
        flushAndClear();

        LocationResponseDTO dto = listAndFind(location.getId());

        assertThat(dto.isHasRepairCapability()).isFalse();
        assertThat(dto.getActiveBayCount()).isZero();
        assertThat(dto.getActiveMobileUnitCount()).isZero();
    }

    @Test
    @DisplayName("#1657 - an unrecognised mobile unit status is not counted as operational")
    void unknownMobileUnitStatus_isNotOperational() {
        Location location = persistLocation("Future Status", true);
        persistMobileUnit(location, "Van 1", "MOTHBALLED");
        flushAndClear();

        LocationResponseDTO dto = listAndFind(location.getId());

        assertThat(dto.isHasRepairCapability()).isFalse();
        assertThat(dto.getActiveMobileUnitCount()).isZero();
    }

    @Test
    @DisplayName("#1657 - an inactive location reports false and zero counts despite an active bay")
    void inactiveLocation_reportsNoRepairCapability() {
        Location location = persistLocation("Closed Shop", false);
        persistBay(location, "Bay 1", BAY_STATUS_ACTIVE);
        persistMobileUnit(location, "Van 1", UNIT_STATUS_ACTIVE);
        flushAndClear();

        LocationResponseDTO dto = listAndFind(location.getId());

        assertThat(dto.isHasRepairCapability()).isFalse();
        assertThat(dto.getActiveBayCount()).isZero();
        assertThat(dto.getActiveMobileUnitCount()).isZero();
    }

    // -------------------------------------------------------------------------
    // AC6 — computed at query time, no cache or refresh step
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#1657 - taking the only bay OUT_OF_SERVICE flips capability in the same run")
    void bayStatusChange_isReflectedImmediately() {
        Location location = persistLocation("Live Update", true);
        BayEntity bay = persistBay(location, "Bay 1", BAY_STATUS_ACTIVE);
        flushAndClear();

        assertThat(listAndFind(location.getId()).isHasRepairCapability()).isTrue();

        em.find(BayEntity.class, bay.getId()).setStatus(BAY_STATUS_OUT_OF_SERVICE);
        flushAndClear();

        LocationResponseDTO afterChange = listAndFind(location.getId());
        assertThat(afterChange.isHasRepairCapability()).isFalse();
        assertThat(afterChange.getActiveBayCount()).isZero();
    }

    @Test
    @DisplayName("#1657 - re-basing a mobile unit moves the count from one location to the other")
    void mobileUnitRebased_movesCountBetweenLocations() {
        Location from = persistLocation("Depot A", true);
        Location to = persistLocation("Depot B", true);
        MobileUnitEntity unit = persistMobileUnit(from, "Van 1", UNIT_STATUS_ACTIVE);
        flushAndClear();

        assertThat(listAndFind(from.getId()).getActiveMobileUnitCount()).isEqualTo(1);
        assertThat(listAndFind(to.getId()).getActiveMobileUnitCount()).isZero();

        em.find(MobileUnitEntity.class, unit.getId()).setBaseLocation(em.getReference(Location.class, to.getId()));
        flushAndClear();

        LocationResponseDTO fromAfter = listAndFind(from.getId());
        LocationResponseDTO toAfter = listAndFind(to.getId());
        assertThat(fromAfter.getActiveMobileUnitCount()).isZero();
        assertThat(fromAfter.isHasRepairCapability()).isFalse();
        assertThat(toAfter.getActiveMobileUnitCount()).isEqualTo(1);
        assertThat(toAfter.isHasRepairCapability()).isTrue();
    }

    // -------------------------------------------------------------------------
    // AC2 — roster projection
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#1657 - the roster carries hasRepairCapability for each location")
    void roster_carriesRepairCapabilityFlag() {
        Location capable = persistLocation("Roster Capable", true);
        Location barren = persistLocation("Roster Barren", true);
        persistBay(capable, "Bay 1", BAY_STATUS_ACTIVE);
        flushAndClear();

        Page<LocationRef> roster = locationRosterService.getRoster(null, null, PageRequest.of(0, 200));

        assertThat(findRef(roster, capable.getId()).isHasRepairCapability()).isTrue();
        assertThat(findRef(roster, barren.getId()).isHasRepairCapability()).isFalse();
    }

    // -------------------------------------------------------------------------
    // AC7 — bounded query count: the projection must not fan out per location
    // -------------------------------------------------------------------------

    /**
     * The list endpoint must cost the same number of statements whatever the size
     * of the result set: one SELECT for the locations plus one aggregate per child
     * table. Reintroducing a per-location lookup makes the five-location run cost
     * strictly more than the one-location run and fails this test.
     */
    @Test
    @DisplayName("#1657 - listing locations costs a fixed number of queries regardless of list size")
    void getAllLocationsDto_usesBoundedQueryCount() {
        long preExistingLocations = countLocations();

        Location first = persistLocation("Budget 1", true);
        persistBay(first, "Bay 1", BAY_STATUS_ACTIVE);
        persistMobileUnit(first, "Van 1", UNIT_STATUS_ACTIVE);
        flushAndClear();

        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        long queriesForOneLocation = measureListStatements(statistics);

        for (int i = 2; i <= 5; i++) {
            Location extra = persistLocation("Budget " + i, true);
            persistBay(extra, "Bay 1", BAY_STATUS_ACTIVE);
        }
        flushAndClear();

        long queriesForFiveLocations = measureListStatements(statistics);

        assertThat(queriesForFiveLocations)
                .as("adding four more locations must not add any query")
                .isEqualTo(queriesForOneLocation);
        assertThat(queriesForOneLocation)
                .as("one SELECT for the locations plus one aggregate over bays and one over mobile units")
                .isLessThanOrEqualTo(3 + preExistingLocations);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private long measureListStatements(Statistics statistics) {
        flushAndClear();
        statistics.clear();
        List<LocationResponseDTO> locations = locationService.getAllLocationsDto();
        assertThat(locations).isNotEmpty();
        return statistics.getPrepareStatementCount();
    }

    private long countLocations() {
        return em.createQuery("SELECT COUNT(l) FROM Location l", Long.class).getSingleResult();
    }

    private LocationResponseDTO listAndFind(UUID locationId) {
        return locationService.getAllLocationsDto().stream()
                .filter(dto -> locationId.equals(dto.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("location " + locationId + " missing from GET /v1/locations"));
    }

    private static LocationRef findRef(Page<LocationRef> roster, UUID locationId) {
        return roster.getContent().stream()
                .filter(ref -> locationId.equals(ref.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("location " + locationId + " missing from the roster"));
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private Location persistLocation(String name, boolean active) {
        String unique = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Location location = Location.builder()
                .name(name + " " + unique)
                .code("RC-" + unique)
                .active(active)
                .status(active ? "ACTIVE" : "INACTIVE")
                .createdAt(now)
                .updatedAt(now)
                .build();
        em.persist(location);
        return location;
    }

    private BayEntity persistBay(Location location, String name, String status) {
        BayEntity bay = BayEntity.builder()
                .location(location)
                .name(name)
                .normalizedName(name.toLowerCase(Locale.ROOT))
                .bayType("REPAIR")
                .status(status)
                .maxConcurrentVehicles(1)
                .build();
        em.persist(bay);
        return bay;
    }

    private MobileUnitEntity persistMobileUnit(Location baseLocation, String name, String status) {
        MobileUnitEntity unit = MobileUnitEntity.builder()
                .name(name + " " + UUID.randomUUID())
                .baseLocation(baseLocation)
                .status(status)
                .build();
        em.persist(unit);
        return unit;
    }
}
