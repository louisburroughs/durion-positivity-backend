package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.invoice.internal.entity.ExtLocationParentReplica;
import com.positivity.invoice.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.invoice.internal.repository.ExtLocationReplicaRepository;
import com.positivity.invoice.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Materialised location-scope ancestor sets on the pos-invoice {@code ext_location} replica
 * (ADR-0061 §2, #1878), driven end to end through {@link LocationEventsListener} against a real
 * database so the edge replacement, the derived traversal queries, the {@code text} column
 * converter and the subtree recompute are all exercised together. The closure itself is pinned
 * in pos-domain-events' {@code LocationAncestryTest}; what matters here is that the module feeds
 * it the right rows and writes the result back where {@link LocationHierarchyService#ancestorsOf}
 * reads it.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_invoice_loc_hier;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("pos-invoice LocationHierarchyService — materialised scope ancestor sets")
class LocationHierarchyServiceTest {

    private static final UUID HQ = id("a1");
    private static final UUID HQ_B = id("a2");
    private static final UUID REGION = id("b1");
    private static final UUID DISTRICT = id("c1");
    private static final UUID SHOP = id("d1");
    private static final UUID ORG_UNIT = id("e1");
    private static final UUID SHIP_HUB = id("e2");
    private static final UUID FIN_ROOT = id("f1");
    private static final UUID FIN_MID = id("f2");

    private static UUID id(String suffix) {
        return UUID.fromString("018f0000-0000-7000-8000-0000000000" + suffix);
    }

    @Autowired
    private ExtLocationReplicaRepository extLocationReplicaRepository;

    @Autowired
    private ExtLocationParentReplicaRepository extLocationParentReplicaRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

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
                Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC),
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
                 "payload":{"locationId":"%s","name":"Loc %s","active":true,
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
    @DisplayName("FINANCIAL: a 3-level chain materialises self plus both financial ancestors, and nothing on OTHER")
    void financialChain() {
        locationFact(FIN_ROOT, 1);
        locationFact(FIN_MID, 1, edge(FIN_ROOT, "FINANCIAL"));
        locationFact(SHOP, 1, edge(FIN_MID, "FINANCIAL"));

        AncestorSets shop = persisted(SHOP);
        assertThat(shop.financial()).containsExactlyInAnyOrder(SHOP, FIN_MID, FIN_ROOT);
        assertThat(shop.other()).containsExactly(SHOP);

        AncestorSets mid = persisted(FIN_MID);
        assertThat(mid.financial()).containsExactlyInAnyOrder(FIN_MID, FIN_ROOT);
    }

    @Test
    @DisplayName("OTHER: parents on four types (incl. SHIPPING) branch into a DAG and shared ancestors are stored once")
    void otherClosureBranchesAndDedupes() {
        locationFact(HQ, 1);
        locationFact(REGION, 1, edge(HQ, "HEADQUARTERS"));
        locationFact(ORG_UNIT, 1, edge(HQ, "HOME_OFFICE"));
        locationFact(SHIP_HUB, 1, edge(HQ, "HEADQUARTERS"));
        locationFact(DISTRICT, 1, edge(REGION, "REGION"));
        locationFact(
                SHOP,
                1,
                edge(DISTRICT, "DISTRICT"),
                edge(REGION, "PHYSICAL"),
                edge(ORG_UNIT, "ORGANIZATIONAL"),
                edge(SHIP_HUB, "SHIPPING"),
                edge(FIN_ROOT, "FINANCIAL"));

        AncestorSets shop = persisted(SHOP);
        // REGION is reached directly and via DISTRICT; HQ via REGION, ORG_UNIT and SHIP_HUB — once each.
        assertThat(shop.other()).containsExactlyInAnyOrder(SHOP, DISTRICT, REGION, ORG_UNIT, SHIP_HUB, HQ);
        assertThat(shop.other()).hasSize(6);
        // The FINANCIAL edge is never crossed on OTHER, and OTHER edges never on FINANCIAL.
        assertThat(shop.other()).doesNotContain(FIN_ROOT);
        assertThat(shop.financial()).containsExactlyInAnyOrder(SHOP, FIN_ROOT);
    }

    @Test
    @DisplayName("inclusive-of-self: a root with no parents is its own sole ancestor on both dimensions")
    void inclusiveOfSelf() {
        locationFact(HQ, 1);

        AncestorSets hq = persisted(HQ);
        assertThat(hq.financial()).containsExactly(HQ);
        assertThat(hq.other()).containsExactly(HQ);
    }

    @Test
    @DisplayName("re-parenting a mid-level node rewrites the sets of every replicated descendant")
    void reparentingPropagatesThreeLevels() {
        // HQ <- REGION <- DISTRICT <- SHOP, with a second HQ_B to move REGION under.
        locationFact(HQ, 1);
        locationFact(HQ_B, 1);
        locationFact(REGION, 1, edge(HQ, "REGION"));
        locationFact(DISTRICT, 1, edge(REGION, "DISTRICT"));
        locationFact(SHOP, 1, edge(DISTRICT, "PHYSICAL"));
        assertThat(persisted(SHOP).other()).containsExactlyInAnyOrder(SHOP, DISTRICT, REGION, HQ);

        // Move REGION from HQ to HQ_B: the fact carries the full replacement edge set.
        locationFact(REGION, 2, edge(HQ_B, "REGION"));

        assertThat(persisted(REGION).other()).containsExactlyInAnyOrder(REGION, HQ_B);
        assertThat(persisted(DISTRICT).other()).containsExactlyInAnyOrder(DISTRICT, REGION, HQ_B);
        AncestorSets shop = persisted(SHOP);
        assertThat(shop.other()).containsExactlyInAnyOrder(SHOP, DISTRICT, REGION, HQ_B);
        assertThat(shop.other()).doesNotContain(HQ);
        // The stored edge set was replaced, not merged.
        assertThat(extLocationParentReplicaRepository.findByChildId(REGION))
                .extracting(ExtLocationParentReplica::getParentId)
                .containsExactly(HQ_B);
    }

    @Test
    @DisplayName("a parent arriving after its child is counted from the edge, then completed when its fact lands")
    void parentArrivingAfterChildTriggersRecompute() {
        // SHOP names FIN_MID before FIN_MID has been replicated: store the edge, compute what we can.
        locationFact(SHOP, 1, edge(FIN_MID, "FINANCIAL"));
        assertThat(persisted(SHOP).financial()).containsExactlyInAnyOrder(SHOP, FIN_MID);
        assertThat(extLocationReplicaRepository.findById(FIN_MID)).isEmpty();

        // FIN_MID's own fact, carrying its parent, must push FIN_ROOT down to SHOP.
        locationFact(FIN_MID, 1, edge(FIN_ROOT, "FINANCIAL"));

        assertThat(persisted(FIN_MID).financial()).containsExactlyInAnyOrder(FIN_MID, FIN_ROOT);
        assertThat(persisted(SHOP).financial()).containsExactlyInAnyOrder(SHOP, FIN_MID, FIN_ROOT);
    }

    @Test
    @DisplayName("a fact without the parents field keeps the existing edges and still refreshes the sets")
    void factWithoutParentsKeepsEdges() {
        locationFact(REGION, 1);
        locationFact(SHOP, 1, edge(REGION, "PHYSICAL"));

        listener.onLocationEvent("""
                {"eventId":"evt-legacy","eventType":"%s","aggregateVersion":2,
                 "payload":{"locationId":"%s","name":"Renamed","active":true}}
                """.formatted(LocationUpdatedV1.EVENT_TYPE, SHOP));

        assertThat(persisted(SHOP).other()).containsExactlyInAnyOrder(SHOP, REGION);
        assertThat(extLocationReplicaRepository.findById(SHOP).orElseThrow().getName())
                .isEqualTo("Renamed");
    }

    @Test
    @DisplayName("an unknown location answers empty sets, which the scope check treats as deny")
    void unknownLocationIsEmpty() {
        AncestorSets unknown = service.ancestorsOf(id("99"));

        assertThat(unknown).isEqualTo(AncestorSets.EMPTY);
        assertThat(unknown.isEmpty()).isTrue();
    }
}
