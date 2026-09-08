package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.LocationParent;
import com.positivity.location.internal.entity.ParentType;
import com.positivity.security.common.LocationAncestorResolver;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-persistence proof for {@link LocationHierarchyService} (ADR-0061, #1872): the walk runs
 * over stored {@code location_parent} rows, and the service is the one
 * {@link LocationAncestorResolver} bean the security filter picks up — without it every scoped
 * caller would be denied everywhere in this module.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("LocationHierarchyService over stored edges (ADR-0061, #1872)")
class LocationHierarchyServiceSpringTest {

    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");

    private static final UUID SITE = UUID.fromString("019200ee-0000-7000-8000-00000000000a");
    private static final UUID REGION = UUID.fromString("019200ee-0000-7000-8000-000000000010");
    private static final UUID FINANCE = UUID.fromString("019200ee-0000-7000-8000-000000000040");
    private static final UUID UNKNOWN = UUID.fromString("019200ee-0000-7000-8000-0000000000ff");

    @Autowired
    private LocationAncestorResolver resolver;

    @Autowired
    private LocationHierarchyService service;

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("the module's LocationAncestorResolver bean is LocationHierarchyService")
    void resolverBeanIsTheHierarchyService() {
        assertThat(resolver).isSameAs(service);
    }

    @Test
    @DisplayName("ancestor sets come from stored edges, per dimension, inclusive of self")
    void ancestorsFromStoredEdges() {
        Location site = persist(SITE, "site");
        Location region = persist(REGION, "region");
        Location finance = persist(FINANCE, "finance");
        link(site, region, ParentType.PHYSICAL);
        link(site, finance, ParentType.FINANCIAL);
        em.flush();
        em.clear();

        AncestorSets sets = resolver.ancestorsOf(SITE);

        assertThat(sets.other()).containsExactlyInAnyOrder(SITE, REGION);
        assertThat(sets.financial()).containsExactlyInAnyOrder(SITE, FINANCE);
        assertThat(resolver.ancestorsOf(REGION).other()).containsExactly(REGION);
    }

    @Test
    @DisplayName("an id with no row answers EMPTY")
    void unknownAnswersEmpty() {
        assertThat(resolver.ancestorsOf(UNKNOWN)).isSameAs(AncestorSets.EMPTY);
    }

    private Location persist(UUID id, String code) {
        Location location = Location.builder()
                .id(id)
                .name(code + " location")
                .normalizedName(code + " location")
                .code("scope-" + code)
                .status("ACTIVE")
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
        em.persist(location);
        return location;
    }

    private void link(Location child, Location parent, ParentType type) {
        em.persist(LocationParent.builder()
                .child(child)
                .parent(parent)
                .parentType(type)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build());
    }
}
