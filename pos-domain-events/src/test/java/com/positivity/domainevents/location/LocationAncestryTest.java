package com.positivity.domainevents.location;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.domainevents.location.LocationAncestry.Closure;
import com.positivity.domainevents.location.LocationAncestry.Dimension;
import com.positivity.domainevents.location.LocationUpdatedV1.ParentRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The pure closure the three location replicas share (ADR-0061 §2, #1878). Edges in, sets out —
 * every property a scope check relies on is pinned here once so the per-module tests only have to
 * prove they feed the walk the right rows.
 */
@DisplayName("LocationAncestry")
class LocationAncestryTest {

    private static final UUID HQ = id("a1");
    private static final UUID REGION = id("a2");
    private static final UUID DISTRICT = id("a3");
    private static final UUID SHOP = id("a4");
    private static final UUID FIN_ROOT = id("b1");
    private static final UUID FIN_MID = id("b2");
    private static final UUID ORG_UNIT = id("c1");

    private static UUID id(String suffix) {
        return UUID.fromString("00000000-0000-7000-8000-0000000000" + suffix);
    }

    /** A tiny in-memory edge store keyed by child. */
    private static final class Edges {
        private final Map<UUID, List<ParentRef>> byChild = new HashMap<>();

        Edges add(UUID child, UUID parent, String type) {
            byChild.computeIfAbsent(child, k -> new ArrayList<>()).add(new ParentRef(parent, type));
            return this;
        }

        List<ParentRef> parentsOf(UUID child) {
            return byChild.getOrDefault(child, List.of());
        }

        List<UUID> childrenOf(UUID parent) {
            List<UUID> children = new ArrayList<>();
            byChild.forEach((child, refs) -> {
                if (refs.stream().anyMatch(ref -> ref.parentId().equals(parent))) {
                    children.add(child);
                }
            });
            return children;
        }
    }

    @Nested
    @DisplayName("FINANCIAL")
    class Financial {

        @Test
        @DisplayName("a 3-level chain yields self plus every financial ancestor, in walk order")
        void threeLevelChain() {
            Edges edges = new Edges().add(SHOP, FIN_MID, "FINANCIAL").add(FIN_MID, FIN_ROOT, "FINANCIAL");

            Closure closure = LocationAncestry.ancestors(SHOP, Dimension.FINANCIAL, edges::parentsOf);

            assertThat(closure.ids()).containsExactly(SHOP, FIN_MID, FIN_ROOT);
            assertThat(closure.truncated()).isFalse();
        }

        @Test
        @DisplayName("ignores every non-financial edge, even on the same node")
        void ignoresOtherEdges() {
            Edges edges = new Edges()
                    .add(SHOP, FIN_MID, "FINANCIAL")
                    .add(SHOP, DISTRICT, "DISTRICT")
                    .add(SHOP, REGION, "PHYSICAL")
                    .add(FIN_MID, HQ, "HEADQUARTERS");

            Set<UUID> ids = LocationAncestry.ancestors(SHOP, Dimension.FINANCIAL, edges::parentsOf)
                    .ids();

            assertThat(ids).containsExactly(SHOP, FIN_MID);
        }
    }

    @Nested
    @DisplayName("OTHER")
    class Other {

        @Test
        @DisplayName("a node with parents on three types branches, and the union dedupes shared ancestors")
        void branchesAndDedupes() {
            // SHOP -> DISTRICT (DISTRICT), SHOP -> REGION (PHYSICAL), SHOP -> ORG_UNIT (ORGANIZATIONAL)
            // DISTRICT -> REGION (REGION), REGION -> HQ (HEADQUARTERS), ORG_UNIT -> HQ (HOME_OFFICE)
            // REGION and HQ are reachable along two paths each and must appear once.
            Edges edges = new Edges()
                    .add(SHOP, DISTRICT, "DISTRICT")
                    .add(SHOP, REGION, "PHYSICAL")
                    .add(SHOP, ORG_UNIT, "ORGANIZATIONAL")
                    .add(DISTRICT, REGION, "REGION")
                    .add(REGION, HQ, "HEADQUARTERS")
                    .add(ORG_UNIT, HQ, "HOME_OFFICE");

            Closure closure = LocationAncestry.ancestors(SHOP, Dimension.OTHER, edges::parentsOf);

            assertThat(closure.ids()).containsExactlyInAnyOrder(SHOP, DISTRICT, REGION, ORG_UNIT, HQ);
            assertThat(closure.ids()).hasSize(5);
            assertThat(closure.truncated()).isFalse();
        }

        @Test
        @DisplayName("a SHIPPING parent contributes to the OTHER set and is walked through")
        void shippingEdgeIsTraversed() {
            UUID shippingHub = id("d1");
            Edges edges = new Edges().add(SHOP, shippingHub, "SHIPPING").add(shippingHub, HQ, "HEADQUARTERS");

            Set<UUID> ids = LocationAncestry.ancestors(SHOP, Dimension.OTHER, edges::parentsOf)
                    .ids();

            assertThat(ids).containsExactly(SHOP, shippingHub, HQ);
            assertThat(LocationAncestry.ancestors(SHOP, Dimension.FINANCIAL, edges::parentsOf)
                            .ids())
                    .containsExactly(SHOP);
        }

        @Test
        @DisplayName("never crosses a FINANCIAL edge")
        void excludesFinancial() {
            Edges edges = new Edges().add(SHOP, FIN_MID, "FINANCIAL").add(SHOP, DISTRICT, "DISTRICT");

            Set<UUID> ids = LocationAncestry.ancestors(SHOP, Dimension.OTHER, edges::parentsOf)
                    .ids();

            assertThat(ids).containsExactly(SHOP, DISTRICT);
        }

        @Test
        @DisplayName("all seven non-financial parent types are traversed and nothing else")
        void dimensionMembership() {
            // pos-location's ParentType has eight constants; OTHER is everything but FINANCIAL.
            assertThat(Dimension.OTHER.parentTypes())
                    .containsExactlyInAnyOrder(
                            "HOME_OFFICE",
                            "HEADQUARTERS",
                            "REGION",
                            "DISTRICT",
                            "PHYSICAL",
                            "ORGANIZATIONAL",
                            "SHIPPING");
            assertThat(Dimension.OTHER.traverses("FINANCIAL")).isFalse();
            assertThat(Dimension.FINANCIAL.parentTypes()).containsExactly("FINANCIAL");
            assertThat(Dimension.FINANCIAL.traverses("PHYSICAL")).isFalse();
        }
    }

    @Nested
    @DisplayName("inclusive-of-self")
    class InclusiveOfSelf {

        @Test
        @DisplayName("a root with no edges is its own sole ancestor on both dimensions")
        void rootIsItsOwnAncestor() {
            AncestorSets sets = LocationAncestry.ancestorSets(HQ, id -> List.of());

            assertThat(sets.financial()).containsExactly(HQ);
            assertThat(sets.other()).containsExactly(HQ);
            assertThat(sets.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("self is the first element of every closure")
        void selfFirst() {
            Edges edges = new Edges().add(SHOP, FIN_MID, "FINANCIAL").add(SHOP, DISTRICT, "DISTRICT");

            AncestorSets sets = LocationAncestry.ancestorSets(SHOP, edges::parentsOf);

            assertThat(sets.financial()).first().isEqualTo(SHOP);
            assertThat(sets.other()).first().isEqualTo(SHOP);
            assertThat(sets.on(Dimension.FINANCIAL)).isEqualTo(sets.financial());
            assertThat(sets.on(Dimension.OTHER)).isEqualTo(sets.other());
        }

        @Test
        @DisplayName("EMPTY is what an unknown location resolves to and reads as deny")
        void emptyIsDeny() {
            assertThat(AncestorSets.EMPTY.isEmpty()).isTrue();
            assertThat(AncestorSets.EMPTY.financial()).isEmpty();
            assertThat(AncestorSets.EMPTY.other()).isEmpty();
        }
    }

    @Nested
    @DisplayName("missing parents")
    class MissingParents {

        @Test
        @DisplayName("an edge to a parent the replica has not received yet still counts the parent")
        void unknownParentIsStillAnAncestor() {
            // SHOP names FIN_MID, but FIN_MID has no stored edges (its fact has not arrived).
            Edges edges = new Edges().add(SHOP, FIN_MID, "FINANCIAL");

            Set<UUID> ids = LocationAncestry.ancestors(SHOP, Dimension.FINANCIAL, edges::parentsOf)
                    .ids();

            assertThat(ids).containsExactly(SHOP, FIN_MID);
        }
    }

    @Nested
    @DisplayName("bounds")
    class Bounds {

        @Test
        @DisplayName("a cycle terminates through the visited set and is not reported as truncated")
        void cycleTerminates() {
            Edges edges = new Edges()
                    .add(SHOP, DISTRICT, "DISTRICT")
                    .add(DISTRICT, REGION, "REGION")
                    .add(REGION, SHOP, "PHYSICAL");

            Closure closure = LocationAncestry.ancestors(SHOP, Dimension.OTHER, edges::parentsOf);

            assertThat(closure.ids()).containsExactly(SHOP, DISTRICT, REGION);
            assertThat(closure.truncated()).isFalse();
        }

        @Test
        @DisplayName("a chain deeper than MAX_DEPTH is cut off and flagged")
        void depthCapTruncates() {
            Edges edges = new Edges();
            UUID child = SHOP;
            List<UUID> chain = new ArrayList<>();
            chain.add(child);
            for (int i = 0; i < LocationAncestry.MAX_DEPTH + 5; i++) {
                UUID parent = UUID.fromString(String.format("00000000-0000-7000-8000-%012d", i + 1));
                edges.add(child, parent, "FINANCIAL");
                chain.add(parent);
                child = parent;
            }

            Closure closure = LocationAncestry.ancestors(SHOP, Dimension.FINANCIAL, edges::parentsOf);

            assertThat(closure.truncated()).isTrue();
            assertThat(closure.ids()).hasSize(LocationAncestry.MAX_DEPTH + 1);
            assertThat(closure.ids()).containsExactlyElementsOf(chain.subList(0, LocationAncestry.MAX_DEPTH + 1));
        }

        @Test
        @DisplayName("a chain exactly at MAX_DEPTH is complete")
        void depthCapBoundary() {
            Edges edges = new Edges();
            UUID child = SHOP;
            for (int i = 0; i < LocationAncestry.MAX_DEPTH - 1; i++) {
                UUID parent = UUID.fromString(String.format("00000000-0000-7000-8000-%012d", i + 1));
                edges.add(child, parent, "FINANCIAL");
                child = parent;
            }

            Closure closure = LocationAncestry.ancestors(SHOP, Dimension.FINANCIAL, edges::parentsOf);

            assertThat(closure.truncated()).isFalse();
            assertThat(closure.ids()).hasSize(LocationAncestry.MAX_DEPTH);
        }
    }

    @Nested
    @DisplayName("descendants")
    class Descendants {

        @Test
        @DisplayName("crosses every edge type downward and excludes the start node")
        void allTypesExclusive() {
            Edges edges = new Edges()
                    .add(REGION, HQ, "REGION")
                    .add(DISTRICT, HQ, "FINANCIAL")
                    .add(SHOP, REGION, "PHYSICAL")
                    .add(ORG_UNIT, SHOP, "ORGANIZATIONAL");

            Closure closure = LocationAncestry.descendants(HQ, edges::childrenOf);

            assertThat(closure.ids()).containsExactlyInAnyOrder(REGION, DISTRICT, SHOP, ORG_UNIT);
            assertThat(closure.ids()).doesNotContain(HQ);
            assertThat(closure.truncated()).isFalse();
        }

        @Test
        @DisplayName("a leaf has no descendants")
        void leaf() {
            assertThat(LocationAncestry.descendants(SHOP, id -> List.of()).ids())
                    .isEmpty();
        }

        @Test
        @DisplayName("a downward cycle terminates")
        void cycleTerminates() {
            Edges edges = new Edges().add(SHOP, HQ, "PHYSICAL").add(HQ, SHOP, "PHYSICAL");

            Closure closure = LocationAncestry.descendants(HQ, edges::childrenOf);

            assertThat(closure.ids()).containsExactly(SHOP);
            assertThat(closure.truncated()).isFalse();
        }
    }

    @Test
    @DisplayName("the walk order is deterministic regardless of the adjacency's iteration order")
    void deterministicOrder() {
        Edges forward = new Edges()
                .add(SHOP, DISTRICT, "DISTRICT")
                .add(SHOP, REGION, "PHYSICAL")
                .add(SHOP, ORG_UNIT, "ORGANIZATIONAL");
        Edges reversed = new Edges()
                .add(SHOP, ORG_UNIT, "ORGANIZATIONAL")
                .add(SHOP, REGION, "PHYSICAL")
                .add(SHOP, DISTRICT, "DISTRICT");

        List<UUID> a = new ArrayList<>(LocationAncestry.ancestors(SHOP, Dimension.OTHER, forward::parentsOf)
                .ids());
        List<UUID> b = new ArrayList<>(LocationAncestry.ancestors(SHOP, Dimension.OTHER, reversed::parentsOf)
                .ids());

        assertThat(a).isEqualTo(b);
        // Sorted by (parentType, parentId): DISTRICT < ORGANIZATIONAL < PHYSICAL.
        assertThat(a).containsExactly(SHOP, DISTRICT, ORG_UNIT, REGION);
    }
}
