package com.positivity.location.internal.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.LocationParent;
import com.positivity.location.internal.entity.ParentType;
import com.positivity.location.internal.repository.LocationParentRepository;
import com.positivity.location.internal.repository.LocationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        properties = {
            "logging.level.health.show-details=INFO",
            "eureka.client.enabled=false",
            "spring.cloud.discovery.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:location_test_db;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driverClassName=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
        })
@ActiveProfiles("test")
class LocationServiceCycleGuardIT {

    @Autowired
    private LocationService locationService;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private LocationParentRepository locationParentRepository;

    @BeforeEach
    void setUp() {
        locationParentRepository.deleteAll();
        locationRepository.deleteAll();
    }

    @AfterEach
    void cleanUp() {
        locationParentRepository.deleteAll();
        locationRepository.deleteAll();
    }

    /** (a) A→B then B→A on the same dimension closes a direct cycle. */
    @Test
    void addParent_directCycleOnSameDimension_rejectedWithCycleDetected() {
        Location a = createLocation("Child-A");
        Location b = createLocation("Parent-B");

        locationService.addParent(a.getId(), b.getId(), "PHYSICAL");

        assertCycleDetected(() -> locationService.addParent(b.getId(), a.getId(), "PHYSICAL"));
        assertEquals(1L, locationParentRepository.count());
    }

    /** (b) A→B→C then C→A on the same dimension closes a three-level cycle. */
    @Test
    void addParent_threeLevelCycleOnSameDimension_rejectedWithCycleDetected() {
        Location a = createLocation("Node-A");
        Location b = createLocation("Node-B");
        Location c = createLocation("Node-C");

        locationService.addParent(a.getId(), b.getId(), "PHYSICAL");
        locationService.addParent(b.getId(), c.getId(), "PHYSICAL");

        assertCycleDetected(() -> locationService.addParent(c.getId(), a.getId(), "PHYSICAL"));
        assertEquals(2L, locationParentRepository.count());
    }

    /**
     * (c) The per-dimension property: the edge that would close a cycle on PHYSICAL is legal on
     * FINANCIAL, because each parentType is its own tree (ADR-0061). Both the direct inverse and
     * the three-level closure are exercised so neither the depth-1 nor the deep walk conflates
     * dimensions.
     */
    @Test
    void addParent_closingEdgeOnDifferentDimension_allowed() {
        Location a = createLocation("Dim-A");
        Location b = createLocation("Dim-B");
        Location c = createLocation("Dim-C");

        locationService.addParent(a.getId(), b.getId(), "PHYSICAL");
        locationService.addParent(b.getId(), c.getId(), "PHYSICAL");

        assertDoesNotThrow(() -> locationService.addParent(b.getId(), a.getId(), "FINANCIAL"));
        assertDoesNotThrow(() -> locationService.addParent(c.getId(), a.getId(), "REGION"));

        assertEquals(4L, locationParentRepository.count());
        assertTrue(locationParentRepository.existsByChild_IdAndParent_IdAndParentType(
                b.getId(), a.getId(), ParentType.FINANCIAL));
        assertTrue(locationParentRepository.existsByChild_IdAndParent_IdAndParentType(
                c.getId(), a.getId(), ParentType.REGION));
        // The PHYSICAL dimension is still guarded on its own (c→b closes b→c; the c→a pair is
        // not reused because (child_id, parent_id) is unique across dimensions).
        assertCycleDetected(() -> locationService.addParent(c.getId(), b.getId(), "PHYSICAL"));
    }

    /** (d) A location cannot be its own parent on any dimension. */
    @Test
    void addParent_selfParent_rejectedWithCycleDetected() {
        Location a = createLocation("Self-A");

        assertCycleDetected(() -> locationService.addParent(a.getId(), a.getId(), "HOME_OFFICE"));
        assertEquals(0L, locationParentRepository.count());
    }

    /**
     * (e) Re-pointing A from B to C, where C is B's parent, does not close a cycle and succeeds.
     * The service exposes no remove-parent operation, so the old edge is dropped through the
     * repository to simulate the re-parent.
     */
    @Test
    void addParent_reparentThatDoesNotCloseCycle_succeeds() {
        Location a = createLocation("Re-A");
        Location b = createLocation("Re-B");
        Location c = createLocation("Re-C");

        locationService.addParent(a.getId(), b.getId(), "PHYSICAL");
        locationService.addParent(b.getId(), c.getId(), "PHYSICAL");

        LocationParent oldEdge = locationParentRepository
                .findByChild_IdAndParentType(a.getId(), ParentType.PHYSICAL)
                .orElseThrow();
        locationParentRepository.delete(oldEdge);
        locationParentRepository.flush();

        assertDoesNotThrow(() -> locationService.addParent(a.getId(), c.getId(), "PHYSICAL"));

        assertEquals(2L, locationParentRepository.count());
        assertTrue(locationParentRepository.existsByChild_IdAndParent_IdAndParentType(
                a.getId(), c.getId(), ParentType.PHYSICAL));
    }

    /**
     * A dimension that already holds a cycle (seeded directly, bypassing the service) must not
     * make the guard loop: the visited set terminates the walk and the new edge is rejected.
     */
    @Test
    void addParent_preExistingCycleOnDimension_guardTerminatesAndRejects() {
        Location x = createLocation("Bad-X");
        Location y = createLocation("Bad-Y");
        Location z = createLocation("Bad-Z");
        locationParentRepository.saveAndFlush(edge(x, y, ParentType.PHYSICAL));
        locationParentRepository.saveAndFlush(edge(y, x, ParentType.PHYSICAL));

        assertCycleDetected(() -> locationService.addParent(z.getId(), x.getId(), "PHYSICAL"));
        assertEquals(2L, locationParentRepository.count());
    }

    @Test
    void deleteAll_whenLocationHasParentRelationship_doesNotThrow() {
        Location child = createLocation("Child-Del");
        Location parent = createLocation("Parent-Del");

        locationService.addParent(child.getId(), parent.getId(), "PHYSICAL");

        assertDoesNotThrow(() -> locationRepository.deleteAll());
    }

    private Location createLocation(String name) {
        Location location = Location.builder().name(name).build();
        return locationRepository.save(location);
    }

    private static LocationParent edge(Location child, Location parent, ParentType parentType) {
        return LocationParent.builder()
                .child(child)
                .parent(parent)
                .parentType(parentType)
                .build();
    }

    private static void assertCycleDetected(Executable call) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, call);
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("CYCLE_DETECTED", ex.getReason());
    }
}
