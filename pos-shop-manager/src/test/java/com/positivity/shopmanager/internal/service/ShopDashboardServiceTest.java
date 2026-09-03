package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shopmanager.internal.dto.ShopDashboardResponse;
import com.positivity.shopmanager.internal.dto.ShopDashboardUnit;
import com.positivity.shopmanager.internal.dto.ShopDashboardWorkorder;
import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtMobileUnitReplica;
import com.positivity.shopmanager.internal.entity.ExtPersonReplica;
import com.positivity.shopmanager.internal.entity.ExtVehicleReplica;
import com.positivity.shopmanager.internal.entity.ExtWorkorderReplica;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.entity.Shop;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.enums.ShopDashboardUnitType;
import com.positivity.shopmanager.internal.exception.LocationNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence-backed tests for {@code GET /v1/shop-dashboard} (issue #1658).
 *
 * <p>The endpoint is a join across four replica tables with a SQL-expressed presentation order and
 * a query budget it must not exceed, so it is exercised against H2 rather than mocked repositories:
 * mocks would happily agree with an ordering the database does not actually produce, and would say
 * nothing at all about how many statements the read costs.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("ShopDashboardServiceTest")
class ShopDashboardServiceTest {

    private static final String VIN = "1HGCM82633A004352";

    @Autowired
    private ShopDashboardService shopDashboardService;

    @Autowired
    private EntityManager em;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private UUID locationId;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        locationId = persistShop("Downtown", "UTC");
        today = LocalDate.now(java.time.ZoneOffset.UTC);
    }

    // -------------------------------------------------------------------------
    // AC1, AC8 — the unit roster is the bay ∪ mobile-unit union, idle units included
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#1658 AC1 - bays and mobile units come back as one discriminated union")
    void unitsAreADiscriminatedUnionOfBaysAndMobileUnits() {
        UUID bayId = persistBay("Front Bay 1", locationId);
        UUID unitId = persistMobileUnit("Van 3", locationId);
        flushAndClear();

        ShopDashboardResponse response = shopDashboardService.getDashboard(locationId, today);

        assertThat(response.units()).hasSize(2);
        assertThat(findUnit(response, bayId).unitType()).isEqualTo(ShopDashboardUnitType.BAY);
        assertThat(findUnit(response, bayId).unitName()).isEqualTo("Front Bay 1");
        assertThat(findUnit(response, unitId).unitType()).isEqualTo(ShopDashboardUnitType.MOBILE_UNIT);
        assertThat(findUnit(response, unitId).unitName()).isEqualTo("Van 3");
    }

    @Test
    @DisplayName("#1658 AC8 - a repair unit with no work is present with a null assignment, not omitted")
    void idleUnitIsPresentWithNullAssignment() {
        UUID bayId = persistBay("Empty Bay", locationId);
        flushAndClear();

        ShopDashboardResponse response = shopDashboardService.getDashboard(locationId, today);

        assertThat(findUnit(response, bayId).assignment()).isNull();
    }

    @Test
    @DisplayName("#1658 AC1 - an occupied unit carries structured vehicle, mechanic name and status")
    void occupiedUnitCarriesVehicleMechanicAndStatus() {
        UUID bayId = persistBay("Bay 1", locationId);
        UUID vehicleId = persistVehicle(VIN, 2024, "Ford", "F-150");
        UUID personId = persistPerson("Ada", "Lovelace");
        persistWorkorder(w -> {
            w.setWorkorderNumber("WO-1");
            w.setStatus("WORK_IN_PROGRESS");
            w.setLocationId(locationId);
            w.setResourceId(bayId);
            w.setResourceType("BAY");
            w.setVehicleId(vehicleId);
            w.setMechanicIds("[\"" + personId + "\"]");
        });
        flushAndClear();

        ShopDashboardWorkorder assignment = findUnit(shopDashboardService.getDashboard(locationId, today), bayId)
                .assignment();

        assertThat(assignment).isNotNull();
        assertThat(assignment.status()).isEqualTo("WORK_IN_PROGRESS");
        assertThat(assignment.workorderNumber()).isEqualTo("WO-1");
        assertThat(assignment.unitName()).isEqualTo("Bay 1");
        assertThat(assignment.unitType()).isEqualTo(ShopDashboardUnitType.BAY);
        assertThat(assignment.vehicle()).isNotNull();
        assertThat(assignment.vehicle().vin()).isEqualTo(VIN);
        assertThat(assignment.vehicle().year()).isEqualTo(2024);
        assertThat(assignment.vehicle().make()).isEqualTo("Ford");
        assertThat(assignment.vehicle().model()).isEqualTo("F-150");
        assertThat(assignment.mechanicName()).isEqualTo("Ada Lovelace");
        assertThat(assignment.mechanicNames()).containsExactly("Ada Lovelace");
    }

    @Test
    @DisplayName("#1658 AC1 - a workorder with two technicians lists both, not just the first")
    void multipleMechanicsAreAllResolved() {
        UUID bayId = persistBay("Bay 1", locationId);
        UUID first = persistPerson("Ada", "Lovelace");
        UUID second = persistPerson("Grace", "Hopper");
        persistWorkorder(w -> {
            w.setWorkorderNumber("WO-1");
            w.setStatus("ASSIGNED");
            w.setLocationId(locationId);
            w.setResourceId(bayId);
            w.setResourceType("BAY");
            w.setMechanicIds("[\"" + first + "\",\"" + second + "\"]");
        });
        flushAndClear();

        ShopDashboardWorkorder assignment = findUnit(shopDashboardService.getDashboard(locationId, today), bayId)
                .assignment();

        assertThat(assignment.mechanicNames()).containsExactly("Ada Lovelace", "Grace Hopper");
    }

    @Test
    @DisplayName("#1658 AC1 - a mechanic missing from the people-contact replica falls back to the HR projection")
    void mechanicNameFallsBackToHrProjection() {
        UUID bayId = persistBay("Bay 1", locationId);
        UUID personId = UUIDv7Generator.generate();
        persistMechanic(personId, "Katherine", "Johnson");
        persistWorkorder(w -> {
            w.setWorkorderNumber("WO-1");
            w.setStatus("ASSIGNED");
            w.setLocationId(locationId);
            w.setResourceId(bayId);
            w.setResourceType("BAY");
            w.setMechanicIds("[\"" + personId + "\"]");
        });
        flushAndClear();

        ShopDashboardWorkorder assignment = findUnit(shopDashboardService.getDashboard(locationId, today), bayId)
                .assignment();

        assertThat(assignment.mechanicName()).isEqualTo("Katherine Johnson");
    }

    // -------------------------------------------------------------------------
    // AC3 — openWorkorders is a superset of the unit list
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#1658 AC3 - an unassigned DRAFT appears in openWorkorders with null unitId and unitName")
    void unassignedDraftAppearsWithNullUnit() {
        persistBay("Bay 1", locationId);
        persistWorkorder(w -> {
            w.setWorkorderNumber("WO-DRAFT");
            w.setStatus("DRAFT");
            w.setLocationId(locationId);
        });
        flushAndClear();

        ShopDashboardResponse response = shopDashboardService.getDashboard(locationId, today);

        assertThat(response.openWorkorders()).hasSize(1);
        ShopDashboardWorkorder row = response.openWorkorders().get(0);
        assertThat(row.unitId()).isNull();
        assertThat(row.unitName()).isNull();
        assertThat(row.unitType()).isNull();
        assertThat(response.units().get(0).assignment()).isNull();
    }

    @Test
    @DisplayName("#1658 AC3 - every open status is included, terminal statuses are not")
    void openStatusesIncludedTerminalExcluded() {
        List<String> open = List.of(
                "DRAFT",
                "APPROVED",
                "ASSIGNED",
                "WORK_IN_PROGRESS",
                "AWAITING_PARTS",
                "AWAITING_APPROVAL",
                "READY_FOR_PICKUP");
        open.forEach(status -> persistWorkorder(w -> {
            w.setWorkorderNumber("WO-" + status);
            w.setStatus(status);
            w.setLocationId(locationId);
        }));
        List.of("COMPLETED", "CANCELLED")
                .forEach(status -> persistWorkorder(w -> {
                    w.setWorkorderNumber("WO-" + status);
                    w.setStatus(status);
                    w.setLocationId(locationId);
                }));
        flushAndClear();

        ShopDashboardResponse response = shopDashboardService.getDashboard(locationId, today);

        assertThat(response.openWorkorders())
                .extracting(ShopDashboardWorkorder::status)
                .containsExactlyInAnyOrderElementsOf(open);
    }

    @Test
    @DisplayName("#1658 AC3 - workorders at another location are not returned")
    void otherLocationsAreExcluded() {
        UUID otherLocation = persistShop("Uptown", "UTC");
        persistWorkorder(w -> {
            w.setWorkorderNumber("WO-HERE");
            w.setStatus("DRAFT");
            w.setLocationId(locationId);
        });
        persistWorkorder(w -> {
            w.setWorkorderNumber("WO-THERE");
            w.setStatus("DRAFT");
            w.setLocationId(otherLocation);
        });
        flushAndClear();

        assertThat(shopDashboardService.getDashboard(locationId, today).openWorkorders())
                .extracting(ShopDashboardWorkorder::workorderNumber)
                .containsExactly("WO-HERE");
    }

    // -------------------------------------------------------------------------
    // AC4 — server-side ordering
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#1658 AC4 - unassigned first, then blocked, queued, active, ready")
    void openWorkordersAreSortedByAssignmentThenStatusBand() {
        UUID bayId = persistBay("Bay 1", locationId);
        persistAssigned("WO-READY", "READY_FOR_PICKUP", bayId);
        persistAssigned("WO-ACTIVE", "WORK_IN_PROGRESS", persistBay("Bay 2", locationId));
        persistAssigned("WO-QUEUED", "ASSIGNED", persistBay("Bay 3", locationId));
        persistAssigned("WO-BLOCKED", "AWAITING_PARTS", persistBay("Bay 4", locationId));
        persistWorkorder(w -> {
            w.setWorkorderNumber("WO-UNASSIGNED");
            w.setStatus("READY_FOR_PICKUP");
            w.setLocationId(locationId);
        });
        flushAndClear();

        assertThat(shopDashboardService.getDashboard(locationId, today).openWorkorders())
                .extracting(ShopDashboardWorkorder::workorderNumber)
                .containsExactly("WO-UNASSIGNED", "WO-BLOCKED", "WO-QUEUED", "WO-ACTIVE", "WO-READY");
    }

    @Test
    @DisplayName("#1658 AC4 - inside a band, an earlier promise sorts first and a missing one sorts last")
    void promisedAtOrdersWithinBandAndNullsSortLast() {
        persistWorkorder(w -> {
            w.setWorkorderNumber("WO-C");
            w.setStatus("DRAFT");
            w.setLocationId(locationId);
        });
        persistWorkorder(w -> {
            w.setWorkorderNumber("WO-B");
            w.setStatus("DRAFT");
            w.setLocationId(locationId);
            w.setPromisedAt(Instant.parse("2026-09-03T17:00:00Z"));
        });
        persistWorkorder(w -> {
            w.setWorkorderNumber("WO-A");
            w.setStatus("DRAFT");
            w.setLocationId(locationId);
            w.setPromisedAt(Instant.parse("2026-09-03T09:00:00Z"));
        });
        flushAndClear();

        assertThat(shopDashboardService.getDashboard(locationId, today).openWorkorders())
                .extracting(ShopDashboardWorkorder::workorderNumber)
                .containsExactly("WO-A", "WO-B", "WO-C");
    }

    // -------------------------------------------------------------------------
    // AC5 — the 200-row cap
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#1658 AC5 - exactly at the cap the list is full and not flagged truncated")
    void atTheCapNothingIsFlagged() {
        for (int i = 0; i < ShopDashboardServiceImpl.OPEN_WORKORDER_CAP; i++) {
            String number = String.format("WO-%04d", i);
            persistWorkorder(w -> {
                w.setWorkorderNumber(number);
                w.setStatus("DRAFT");
                w.setLocationId(locationId);
            });
        }
        flushAndClear();

        ShopDashboardResponse response = shopDashboardService.getDashboard(locationId, today);

        assertThat(response.openWorkorders()).hasSize(ShopDashboardServiceImpl.OPEN_WORKORDER_CAP);
        assertThat(response.openWorkordersTruncated()).isFalse();
    }

    @Test
    @DisplayName("#1658 AC5 - past the cap the list is cut to 200 and openWorkordersTruncated is true")
    void pastTheCapTheListIsCutAndFlagged() {
        for (int i = 0; i < ShopDashboardServiceImpl.OPEN_WORKORDER_CAP + 5; i++) {
            String number = String.format("WO-%04d", i);
            persistWorkorder(w -> {
                w.setWorkorderNumber(number);
                w.setStatus("DRAFT");
                w.setLocationId(locationId);
            });
        }
        flushAndClear();

        ShopDashboardResponse response = shopDashboardService.getDashboard(locationId, today);

        assertThat(response.openWorkorders()).hasSize(ShopDashboardServiceImpl.OPEN_WORKORDER_CAP);
        assertThat(response.openWorkordersTruncated()).isTrue();
        // The cap is applied after the sort, so what survives is the first page of the order the
        // operator was promised — not an arbitrary 200 rows.
        assertThat(response.openWorkorders().get(0).workorderNumber()).isEqualTo("WO-0000");
    }

    /**
     * The cap applies to {@code openWorkorders}, and the sort puts unassigned work first — so at a
     * busy location the assigned rows are exactly the ones that fall past the cut. Deriving the
     * unit roster's occupancy from the capped list would therefore report a busy bay as free, which
     * is the worst answer this endpoint can give: an empty bay is what a dispatcher acts on.
     */
    @Test
    @DisplayName("#1658 AC5/AC1 - a unit whose workorder falls past the cap still reads as occupied")
    void occupancySurvivesTheOpenWorkorderCap() {
        UUID bayId = persistBay("Bay 1", locationId);
        for (int i = 0; i < ShopDashboardServiceImpl.OPEN_WORKORDER_CAP; i++) {
            String number = String.format("WO-%04d", i);
            persistWorkorder(w -> {
                w.setWorkorderNumber(number);
                w.setStatus("DRAFT");
                w.setLocationId(locationId);
            });
        }
        persistAssigned("WO-9999", "WORK_IN_PROGRESS", bayId);
        flushAndClear();

        ShopDashboardResponse response = shopDashboardService.getDashboard(locationId, today);

        assertThat(response.openWorkordersTruncated()).isTrue();
        assertThat(response.openWorkorders())
                .extracting(ShopDashboardWorkorder::workorderNumber)
                .doesNotContain("WO-9999");
        assertThat(findUnit(response, bayId).assignment()).isNotNull();
        assertThat(findUnit(response, bayId).assignment().workorderNumber()).isEqualTo("WO-9999");
    }

    // -------------------------------------------------------------------------
    // AC6, AC7 — occupancy falls out of the open-status definition
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#1658 AC6 - a unit whose workorder is COMPLETED or CANCELLED reads as unassigned")
    void closedWorkorderFreesItsUnit() {
        UUID completedBay = persistBay("Bay 1", locationId);
        UUID cancelledBay = persistBay("Bay 2", locationId);
        persistAssigned("WO-DONE", "COMPLETED", completedBay);
        persistAssigned("WO-VOID", "CANCELLED", cancelledBay);
        flushAndClear();

        ShopDashboardResponse response = shopDashboardService.getDashboard(locationId, today);

        assertThat(findUnit(response, completedBay).assignment()).isNull();
        assertThat(findUnit(response, cancelledBay).assignment()).isNull();
        assertThat(response.openWorkorders()).isEmpty();
    }

    @Test
    @DisplayName("#1658 AC7 - a READY_FOR_PICKUP workorder still occupies its unit")
    void readyForPickupStillOccupiesTheUnit() {
        UUID bayId = persistBay("Bay 1", locationId);
        persistAssigned("WO-READY", "READY_FOR_PICKUP", bayId);
        flushAndClear();

        ShopDashboardWorkorder assignment = findUnit(shopDashboardService.getDashboard(locationId, today), bayId)
                .assignment();

        assertThat(assignment).isNotNull();
        assertThat(assignment.status()).isEqualTo("READY_FOR_PICKUP");
    }

    // -------------------------------------------------------------------------
    // AC2 — date scopes the unit roster only
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#1658 AC2 - date scopes the unit roster but never openWorkorders")
    void dateScopesUnitsOnly() {
        UUID bayId = persistBay("Bay 1", locationId);
        persistWorkorder(w -> {
            w.setWorkorderNumber("WO-TOMORROW");
            w.setStatus("ASSIGNED");
            w.setLocationId(locationId);
            w.setResourceId(bayId);
            w.setResourceType("BAY");
            w.setScheduledDate(today.plusDays(1));
        });
        flushAndClear();

        ShopDashboardResponse forToday = shopDashboardService.getDashboard(locationId, today);
        assertThat(findUnit(forToday, bayId).assignment()).isNull();
        assertThat(forToday.openWorkorders())
                .extracting(ShopDashboardWorkorder::workorderNumber)
                .containsExactly("WO-TOMORROW");

        ShopDashboardResponse forTomorrow = shopDashboardService.getDashboard(locationId, today.plusDays(1));
        assertThat(findUnit(forTomorrow, bayId).assignment()).isNotNull();
        assertThat(forTomorrow.openWorkorders()).hasSize(1);
    }

    @Test
    @DisplayName("#1658 AC2 - work with no scheduled date occupies its unit on any requested day")
    void undatedWorkOccupiesItsUnitOnAnyDay() {
        UUID bayId = persistBay("Bay 1", locationId);
        persistAssigned("WO-NOW", "WORK_IN_PROGRESS", bayId);
        flushAndClear();

        assertThat(findUnit(shopDashboardService.getDashboard(locationId, today.plusDays(30)), bayId)
                        .assignment())
                .isNotNull();
    }

    // -------------------------------------------------------------------------
    // AC12 — errors
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#1658 AC12 - an unknown location is a 404, not an empty dashboard")
    void unknownLocationIsNotFound() {
        UUID unknown = UUIDv7Generator.generate();

        assertThatThrownBy(() -> shopDashboardService.getDashboard(unknown, today))
                .isInstanceOf(LocationNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // AC13 — the query budget does not grow with the data
    // -------------------------------------------------------------------------

    /**
     * The N+1 this pins down is the obvious implementation of the endpoint: resolve each unit's
     * workorder, then each workorder's vehicle, then each workorder's mechanic. That version costs
     * three statements per unit and answers every functional assertion above identically — only a
     * statement count can tell the two apart.
     *
     * <p>Growing from one unit and one workorder to five of each must add zero statements. The
     * absolute budget is asserted too, so a future change that batches per-row lookups into one
     * extra round trip per call still has to be a deliberate edit to this number.
     */
    @Test
    @DisplayName("#1658 AC13 - the dashboard costs a fixed number of queries regardless of size")
    void dashboardUsesBoundedQueryCount() {
        UUID bay = persistBay("Bay 1", locationId);
        persistOccupiedBay(bay, "WO-0001");
        flushAndClear();

        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        long queriesForOneUnit = measureDashboardStatements(statistics);

        for (int i = 2; i <= 5; i++) {
            persistOccupiedBay(persistBay("Bay " + i, locationId), String.format("WO-%04d", i));
        }
        flushAndClear();

        long queriesForFiveUnits = measureDashboardStatements(statistics);

        assertThat(queriesForFiveUnits)
                .as("adding four more occupied units must not add any query")
                .isEqualTo(queriesForOneUnit);
        assertThat(queriesForOneUnit)
                .as("shop, bays, mobile units, open work, occupancy, vehicles, persons, mechanics")
                .isBetween(5L, 8L);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private long measureDashboardStatements(Statistics statistics) {
        flushAndClear();
        statistics.clear();
        ShopDashboardResponse response = shopDashboardService.getDashboard(locationId, today);
        assertThat(response.units()).isNotEmpty();
        assertThat(response.openWorkorders()).isNotEmpty();
        return statistics.getPrepareStatementCount();
    }

    private void persistOccupiedBay(UUID bayId, String workorderNumber) {
        UUID vehicleId = persistVehicle(VIN, 2024, "Ford", "F-150");
        UUID personId = persistPerson("Tech", workorderNumber);
        persistWorkorder(w -> {
            w.setWorkorderNumber(workorderNumber);
            w.setStatus("WORK_IN_PROGRESS");
            w.setLocationId(locationId);
            w.setResourceId(bayId);
            w.setResourceType("BAY");
            w.setVehicleId(vehicleId);
            w.setMechanicIds("[\"" + personId + "\"]");
        });
    }

    private ShopDashboardUnit findUnit(ShopDashboardResponse response, UUID unitId) {
        return response.units().stream()
                .filter(unit -> unitId.equals(unit.unitId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("unit " + unitId + " missing from the dashboard"));
    }

    private UUID persistShop(String name, String timezone) {
        Shop shop = Shop.builder().name(name).timezone(timezone).build();
        em.persist(shop);
        return shop.getId();
    }

    private UUID persistBay(String name, UUID location) {
        UUID id = UUIDv7Generator.generate();
        em.persist(ExtBayReplica.builder()
                .bayId(id)
                .locationId(location)
                .name(name)
                .active(true)
                .aggregateVersion(1)
                .updatedAt(Instant.now())
                .build());
        return id;
    }

    private UUID persistMobileUnit(String name, UUID location) {
        UUID id = UUIDv7Generator.generate();
        em.persist(ExtMobileUnitReplica.builder()
                .mobileUnitId(id)
                .baseLocationId(location)
                .name(name)
                .active(true)
                .aggregateVersion(1)
                .updatedAt(Instant.now())
                .build());
        return id;
    }

    private UUID persistVehicle(String vin, int year, String make, String model) {
        UUID id = UUIDv7Generator.generate();
        em.persist(ExtVehicleReplica.builder()
                .vehicleId(id)
                .accountId(UUIDv7Generator.generate())
                .vin(vin)
                .year(year)
                .make(make)
                .model(model)
                .active(true)
                .aggregateVersion(1)
                .updatedAt(Instant.now())
                .build());
        return id;
    }

    private UUID persistPerson(String firstName, String lastName) {
        UUID id = UUIDv7Generator.generate();
        em.persist(ExtPersonReplica.builder()
                .personId(id)
                .firstName(firstName)
                .lastName(lastName)
                .aggregateVersion(1)
                .updatedAt(Instant.now())
                .build());
        return id;
    }

    private void persistMechanic(UUID personId, String firstName, String lastName) {
        em.persist(Mechanic.builder()
                .personId(personId.toString())
                .firstName(firstName)
                .lastName(lastName)
                .status(MechanicStatus.ACTIVE)
                .build());
    }

    private void persistAssigned(String workorderNumber, String status, UUID resourceId) {
        persistWorkorder(w -> {
            w.setWorkorderNumber(workorderNumber);
            w.setStatus(status);
            w.setLocationId(locationId);
            w.setResourceId(resourceId);
            w.setResourceType("BAY");
        });
    }

    private void persistWorkorder(java.util.function.Consumer<ExtWorkorderReplica> customizer) {
        ExtWorkorderReplica row = new ExtWorkorderReplica();
        row.setWorkorderId(UUIDv7Generator.generate());
        row.setAggregateVersion(1);
        row.setUpdatedAt(Instant.now());
        customizer.accept(row);
        em.persist(row);
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
