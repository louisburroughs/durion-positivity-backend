package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.domainevents.location.LocationAncestry.Dimension;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.security.common.LocationScope.Reach;
import com.positivity.warranty.internal.config.JpaConfig;
import com.positivity.warranty.internal.entity.ExtLocationParentReplica;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.enums.ClaimType;
import com.positivity.warranty.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.warranty.internal.repository.ExtLocationReplicaRepository;
import com.positivity.warranty.internal.repository.ProcessedEventRepository;
import com.positivity.warranty.internal.repository.WarrantyClaimRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * The pos-warranty location replica added by #1885, driven end to end through
 * {@link LocationEventsListener} against a real database: the {@code V6} migration, the edge
 * replacement, the {@code text} ancestor-set converter, the subtree recompute and the downward
 * reach expansion the narrowed claim search depends on.
 *
 * <p>The closure itself is pinned in pos-domain-events' {@code LocationAncestryTest}; what matters
 * here is that this module feeds it the right rows, writes the result back where
 * {@link LocationHierarchyService#ancestorsOf} reads it, and answers nothing at all for a location
 * it does not hold — the value the scope check reads as deny.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_warranty_loc_hier;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, LocationHierarchyServiceTest.ClockConfig.class})
@DisplayName("pos-warranty LocationHierarchyService — materialised scope ancestor sets (#1885)")
class LocationHierarchyServiceTest {

    private static final UUID HQ = id("a1");
    private static final UUID HQ_B = id("a2");
    private static final UUID REGION = id("b1");
    private static final UUID DISTRICT = id("c1");
    private static final UUID SHOP = id("d1");
    private static final UUID OTHER_SHOP = id("d2");
    private static final UUID FIN_ROOT = id("f1");
    private static final UUID UNKNOWN = id("ff");
    private static final UUID CUSTOMER = id("c9");
    private static final UUID VEHICLE = id("e9");

    private static UUID id(String suffix) {
        return UUID.fromString("018f0000-0000-7000-8000-0000000000" + suffix);
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private ExtLocationReplicaRepository extLocationReplicaRepository;

    @Autowired
    private ExtLocationParentReplicaRepository extLocationParentReplicaRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private WarrantyClaimRepository claimRepository;

    @Autowired
    private TestEntityManager entityManager;

    private LocationHierarchyService service;
    private LocationEventsListener listener;
    private final AtomicInteger eventSequence = new AtomicInteger();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new LocationHierarchyService(extLocationReplicaRepository, extLocationParentReplicaRepository);
        listener = new LocationEventsListener(
                Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC),
                new ObjectMapper(),
                processedEventRepository,
                extLocationReplicaRepository,
                extLocationParentReplicaRepository,
                service,
                Mockito.mock(ObjectProvider.class));
    }

    /** Feeds one {@code location.location.updated} fact; {@code edges} are {@code "parentId:TYPE"}. */
    private void locationFact(UUID locationId, long version, String... edges) {
        StringBuilder parents = new StringBuilder("[");
        for (int i = 0; i < edges.length; i++) {
            String[] parts = edges[i].split(":");
            if (i > 0) {
                parents.append(',');
            }
            parents.append("{\"parentId\":\"")
                    .append(parts[0])
                    .append("\",\"parentType\":\"")
                    .append(parts[1])
                    .append("\"}");
        }
        parents.append(']');
        listener.onLocationEvent("""
                {"eventId":"evt-%d","eventType":"%s","aggregateVersion":%d,
                 "payload":{"locationId":"%s","name":"Loc %s","status":"ACTIVE","active":true,
                            "parents":%s}}
                """.formatted(
                        eventSequence.incrementAndGet(),
                        LocationUpdatedV1.EVENT_TYPE,
                        version,
                        locationId,
                        locationId,
                        parents));
    }

    private static String edge(UUID parent, String type) {
        return parent + ":" + type;
    }

    /** Reads back through the database, not the persistence context, so the converter is exercised. */
    private AncestorSets persisted(UUID locationId) {
        entityManager.flush();
        entityManager.clear();
        return service.ancestorsOf(locationId);
    }

    @Test
    @DisplayName("a location the replica does not hold answers EMPTY, which the scope check reads as deny")
    void unknownLocationIsEmpty() {
        assertThat(service.ancestorsOf(UNKNOWN)).isEqualTo(AncestorSets.EMPTY);
        assertThat(service.descendantsOf(UNKNOWN, Dimension.OTHER)).isEmpty();
    }

    @Test
    @DisplayName("the OTHER closure branches across types and never crosses a FINANCIAL edge")
    void otherClosureBranchesAndStaysOnDimension() {
        locationFact(HQ, 1);
        locationFact(FIN_ROOT, 1);
        locationFact(REGION, 1, edge(HQ, "HEADQUARTERS"));
        locationFact(DISTRICT, 1, edge(REGION, "REGION"));
        locationFact(SHOP, 1, edge(DISTRICT, "DISTRICT"), edge(FIN_ROOT, "FINANCIAL"));

        AncestorSets shop = persisted(SHOP);
        assertThat(shop.other()).containsExactlyInAnyOrder(SHOP, DISTRICT, REGION, HQ);
        assertThat(shop.other()).doesNotContain(FIN_ROOT);
        assertThat(shop.financial()).containsExactlyInAnyOrder(SHOP, FIN_ROOT);
    }

    @Test
    @DisplayName("re-parenting a mid-level node rewrites the sets of every replicated descendant")
    void reparentingPropagates() {
        locationFact(HQ, 1);
        locationFact(HQ_B, 1);
        locationFact(REGION, 1, edge(HQ, "REGION"));
        locationFact(SHOP, 1, edge(REGION, "PHYSICAL"));
        assertThat(persisted(SHOP).other()).containsExactlyInAnyOrder(SHOP, REGION, HQ);

        locationFact(REGION, 2, edge(HQ_B, "REGION"));

        assertThat(persisted(SHOP).other()).containsExactlyInAnyOrder(SHOP, REGION, HQ_B);
        assertThat(extLocationParentReplicaRepository.findByChildId(REGION))
                .extracting(ExtLocationParentReplica::getParentId)
                .containsExactly(HQ_B);
    }

    @Test
    @DisplayName("reach expands a region node downward to itself and every replicated shop beneath it")
    void reachExpandsDownward() {
        locationFact(HQ, 1);
        locationFact(REGION, 1, edge(HQ, "REGION"));
        locationFact(SHOP, 1, edge(REGION, "PHYSICAL"));
        locationFact(OTHER_SHOP, 1, edge(HQ, "PHYSICAL"));
        entityManager.flush();
        entityManager.clear();

        assertThat(service.descendantsOf(REGION, Dimension.OTHER)).containsExactlyInAnyOrder(REGION, SHOP);
        assertThat(service.descendantsOf(REGION, Dimension.FINANCIAL)).containsExactly(REGION);
        assertThat(service.reachableLocations(new Reach(Set.of(Dimension.OTHER), Set.of(REGION))))
                .containsExactlyInAnyOrder(REGION, SHOP);
        assertThat(service.reachableLocations(new Reach(Set.of(Dimension.OTHER), Set.of())))
                .isEmpty();
    }

    @Test
    @DisplayName("the narrowed claim query returns only claims at a reachable location, and never a location-less one")
    void narrowedClaimQueryRestrictsToReach() {
        claimRepository.save(claim("WC-2026-000001", SHOP));
        claimRepository.save(claim("WC-2026-000002", OTHER_SHOP));
        claimRepository.save(claim("WC-2026-000003", null));
        entityManager.flush();
        entityManager.clear();

        assertThat(claimRepository
                        .searchWithinLocations(null, null, null, Set.of(REGION, SHOP), PageRequest.of(0, 20))
                        .getContent())
                .extracting(WarrantyClaim::getClaimCode)
                .containsExactly("WC-2026-000001");
    }

    private static WarrantyClaim claim(String code, UUID locationId) {
        return WarrantyClaim.builder()
                .claimCode(code)
                .locationId(locationId)
                .claimType(ClaimType.MANUFACTURER_DEFECT)
                .customerId(CUSTOMER)
                .vehicleId(VEHICLE)
                .status(ClaimStatus.DRAFT)
                .build();
    }
}
