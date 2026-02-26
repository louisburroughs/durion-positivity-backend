package com.positivity.location.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.ParentType;
import com.positivity.location.internal.repository.LocationParentRepository;
import com.positivity.location.internal.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
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

    @Test
    void addParent_WhenReverseRelationshipExists_ThrowsException() {
        Location child = createLocation("Child-A");
        Location parent = createLocation("Parent-B");

        locationService.addParent(child.getId(), parent.getId(), ParentType.PHYSICAL);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> locationService.addParent(parent.getId(), child.getId(), ParentType.PHYSICAL));

        assertTrue(ex.getMessage().contains("inverse relationship already exists"));
        assertEquals(1L, locationParentRepository.count());
    }

    @Test
    void addParent_WhenParentIsDescendant_ThrowsException() {
        Location a = createLocation("Node-A");
        Location b = createLocation("Node-B");
        Location c = createLocation("Node-C");

        locationService.addParent(a.getId(), b.getId(), ParentType.PHYSICAL);
        locationService.addParent(b.getId(), c.getId(), ParentType.PHYSICAL);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> locationService.addParent(c.getId(), a.getId(), ParentType.PHYSICAL));

        assertTrue(ex.getMessage().contains("parent is a descendant of child"));
        assertEquals(2L, locationParentRepository.count());
    }

    private Location createLocation(String name) {
        Location location = Location.builder()
                .name(name)
                .build();
        return locationRepository.save(location);
    }
}
