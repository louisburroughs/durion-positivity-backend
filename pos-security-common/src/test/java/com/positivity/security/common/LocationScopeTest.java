package com.positivity.security.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.domainevents.location.LocationAncestry.Dimension;
import com.positivity.security.common.LocationScope.Reach;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * Every row of the ADR-0061 §2 decision table, pinned (#1870).
 *
 * <p>The fixture is a small two-dimensional hierarchy held in a stub resolver — the shape each
 * module's {@code LocationHierarchyService} answers from its replica. The FINANCIAL and OTHER
 * chains deliberately disagree about who is above the shop, because the whole point of two
 * bitsets is that a financial rollup and an operational rollup are different questions.
 *
 * <pre>
 *   OTHER:      REGION ─┬─ SHOP
 *   FINANCIAL:  LEDGER ─┴─ SHOP     (REGION is not a financial ancestor; LEDGER is not an operational one)
 * </pre>
 */
@DisplayName("LocationScope — the shared covers(P, L) decision")
class LocationScopeTest {

    private static final UUID REGION = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID LEDGER = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID SHOP = UUID.fromString("00000000-0000-7000-8000-000000000003");
    private static final UUID OTHER_SHOP = UUID.fromString("00000000-0000-7000-8000-000000000004");
    private static final UUID UNREPLICATED = UUID.fromString("00000000-0000-7000-8000-0000000000ff");

    private static final String WIP_VIEW = "workorder:wip:view";
    private static final String JE_POST = "accounting:je:post";
    private static final String GLOBAL_PERMISSION = "crm:party:view";

    /** Stub resolver over the fixture; unknown ids answer EMPTY, as the SPI requires. */
    private static final LocationAncestorResolver RESOLVER = new LocationAncestorResolver() {
        private final Map<UUID, AncestorSets> sets = new HashMap<>();

        {
            sets.put(SHOP, new AncestorSets(Set.of(SHOP, LEDGER), Set.of(SHOP, REGION)));
            sets.put(OTHER_SHOP, new AncestorSets(Set.of(OTHER_SHOP), Set.of(OTHER_SHOP)));
            sets.put(REGION, new AncestorSets(Set.of(REGION), Set.of(REGION)));
            sets.put(LEDGER, new AncestorSets(Set.of(LEDGER), Set.of(LEDGER)));
        }

        @Override
        public AncestorSets ancestorsOf(UUID locationId) {
            return sets.getOrDefault(locationId, AncestorSets.EMPTY);
        }
    };

    private static LocationScope scope(Set<String> financial, Set<String> other, UUID... nodes) {
        return LocationScope.of(financial, other, Optional.of(Set.of(nodes)), true, RESOLVER);
    }

    @Nested
    @DisplayName("rows that need no hierarchy at all")
    class NoHierarchyRows {

        @Test
        @DisplayName("claims absent → allow, whatever else is true (pre-rollout behaviour preserved)")
        void claimsAbsentAllows() {
            LocationScope unscoped = LocationScope.unscoped();

            assertThat(unscoped.claimsPresent()).isFalse();
            assertThat(unscoped.covers(WIP_VIEW, SHOP.toString())).isTrue();
            assertThat(unscoped.covers(WIP_VIEW, "not-a-uuid")).isTrue();
            assertThatCode(() -> unscoped.require(WIP_VIEW, UNREPLICATED)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("claimsPresent=false is permissive even when sets and nodes were supplied")
        void claimsPresentFlagWins() {
            LocationScope scope = LocationScope.of(Set.of(WIP_VIEW), Set.of(), Optional.empty(), false, RESOLVER);

            // The flag, not the emptiness of the sets, is what says "this token predates scope".
            assertThat(scope.covers(WIP_VIEW, OTHER_SHOP)).isTrue();
        }

        @Test
        @DisplayName("P in neither set → allow: the grant is global, and L is not even inspected")
        void globalPermissionAllows() {
            LocationScope scope = scope(Set.of(JE_POST), Set.of(WIP_VIEW), SHOP);

            assertThat(scope.isScoped(GLOBAL_PERMISSION)).isFalse();
            assertThat(scope.covers(GLOBAL_PERMISSION, OTHER_SHOP)).isTrue();
            assertThat(scope.covers(GLOBAL_PERMISSION, UNREPLICATED)).isTrue();
            assertThat(scope.covers(GLOBAL_PERMISSION, "not-a-uuid")).isTrue();
        }

        @Test
        @DisplayName("empty bitsets with claims present → every permission is global")
        void emptyBitsetsAreGlobal() {
            LocationScope scope = LocationScope.of(Set.of(), Set.of(), Optional.empty(), true, RESOLVER);

            assertThat(scope.claimsPresent()).isTrue();
            assertThat(scope.covers(WIP_VIEW, OTHER_SHOP)).isTrue();
            assertThat(scope.covers(JE_POST, UNREPLICATED)).isTrue();
        }

        @Test
        @DisplayName("P in a set, nodes absent → deny (fail closed: bits present, no assignment)")
        void scopedWithoutNodesDenies() {
            LocationScope scope = LocationScope.of(Set.of(), Set.of(WIP_VIEW), Optional.empty(), true, RESOLVER);

            assertThat(scope.nodes()).isEmpty();
            assertThat(scope.covers(WIP_VIEW, SHOP)).isFalse();
            assertThat(scope.covers(GLOBAL_PERMISSION, SHOP)).isTrue();
        }

        @Test
        @DisplayName("P in a set, nodes present but empty → deny")
        void scopedWithEmptyNodesDenies() {
            LocationScope scope = LocationScope.of(Set.of(), Set.of(WIP_VIEW), Optional.of(Set.of()), true, RESOLVER);

            assertThat(scope.covers(WIP_VIEW, SHOP)).isFalse();
        }
    }

    @Nested
    @DisplayName("rows that intersect the assigned nodes with the ancestor sets")
    class HierarchyRows {

        @Test
        @DisplayName("OTHER dimension: a directly assigned node matches")
        void otherDirectNodeCovers() {
            assertThat(scope(Set.of(), Set.of(WIP_VIEW), SHOP).covers(WIP_VIEW, SHOP))
                    .isTrue();
        }

        @Test
        @DisplayName("OTHER dimension: an ancestor on the OTHER chain matches by the same rule")
        void otherAncestorCovers() {
            assertThat(scope(Set.of(), Set.of(WIP_VIEW), REGION).covers(WIP_VIEW, SHOP))
                    .isTrue();
        }

        @Test
        @DisplayName("OTHER dimension: an ancestor on the FINANCIAL chain does not count")
        void otherIgnoresFinancialAncestor() {
            // LEDGER is above SHOP financially but not operationally: an operational role assigned
            // at LEDGER does not run the shop.
            assertThat(scope(Set.of(), Set.of(WIP_VIEW), LEDGER).covers(WIP_VIEW, SHOP))
                    .isFalse();
        }

        @Test
        @DisplayName("FINANCIAL dimension: a directly assigned node matches")
        void financialDirectNodeCovers() {
            assertThat(scope(Set.of(JE_POST), Set.of(), SHOP).covers(JE_POST, SHOP))
                    .isTrue();
        }

        @Test
        @DisplayName("FINANCIAL dimension: an ancestor on the FINANCIAL chain matches")
        void financialAncestorCovers() {
            assertThat(scope(Set.of(JE_POST), Set.of(), LEDGER).covers(JE_POST, SHOP))
                    .isTrue();
        }

        @Test
        @DisplayName("FINANCIAL dimension: an ancestor on the OTHER chain does not count")
        void financialIgnoresOtherAncestor() {
            assertThat(scope(Set.of(JE_POST), Set.of(), REGION).covers(JE_POST, SHOP))
                    .isFalse();
        }

        @Test
        @DisplayName("disjoint on the one dimension P is scoped on → deny")
        void disjointDenies() {
            assertThat(scope(Set.of(), Set.of(WIP_VIEW), OTHER_SHOP).covers(WIP_VIEW, SHOP))
                    .isFalse();
            assertThat(scope(Set.of(JE_POST), Set.of(), OTHER_SHOP).covers(JE_POST, SHOP))
                    .isFalse();
        }

        @Test
        @DisplayName("P in both sets passes when either dimension covers")
        void bothSetsEitherCovers() {
            Set<String> both = Set.of(WIP_VIEW);

            // Covered operationally only (REGION), financially only (LEDGER), and neither.
            assertThat(scope(both, both, REGION).covers(WIP_VIEW, SHOP)).isTrue();
            assertThat(scope(both, both, LEDGER).covers(WIP_VIEW, SHOP)).isTrue();
            assertThat(scope(both, both, OTHER_SHOP).covers(WIP_VIEW, SHOP)).isFalse();
        }

        @Test
        @DisplayName("several assigned nodes: any one of them may cover")
        void multiNodeAssignment() {
            LocationScope scope = scope(Set.of(), Set.of(WIP_VIEW), OTHER_SHOP, REGION);

            assertThat(scope.covers(WIP_VIEW, SHOP)).isTrue();
            assertThat(scope.covers(WIP_VIEW, OTHER_SHOP)).isTrue();
        }

        @Test
        @DisplayName("L unknown to the resolver → deny, even when the node is assigned directly")
        void unknownLocationDenies() {
            // A location the replica has not received yet answers EMPTY; a stale replica must not
            // grant, so the intersection is empty and the check denies.
            assertThat(scope(Set.of(), Set.of(WIP_VIEW), UNREPLICATED).covers(WIP_VIEW, UNREPLICATED))
                    .isFalse();
        }

        @ParameterizedTest(name = "L = [{0}]")
        @ValueSource(strings = {"", "not-a-uuid", "00000000-0000-7000-8000-00000000000", "SHOP"})
        @DisplayName("L that is not a UUID → deny for a scoped P")
        void nonUuidLocationDenies(String locationId) {
            assertThat(scope(Set.of(), Set.of(WIP_VIEW), SHOP).covers(WIP_VIEW, locationId))
                    .isFalse();
        }

        @Test
        @DisplayName("the PERM_ prefix the gateway uses is accepted and normalised")
        void permPrefixNormalised() {
            LocationScope scope = scope(Set.of(), Set.of(WIP_VIEW), REGION);

            assertThat(scope.isScoped("PERM_" + WIP_VIEW)).isTrue();
            assertThat(scope.covers("PERM_" + WIP_VIEW, SHOP)).isTrue();
            assertThat(scope.covers("PERM_" + WIP_VIEW, OTHER_SHOP)).isFalse();
        }
    }

    @Nested
    @DisplayName("require(P, L)")
    class Require {

        @Test
        @DisplayName("passes silently when covered")
        void passesWhenCovered() {
            assertThatCode(() -> scope(Set.of(), Set.of(WIP_VIEW), REGION).require(WIP_VIEW, SHOP))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws LocationScopeDeniedException carrying the permission and the distinguishing code")
        void throwsWhenDenied() {
            LocationScope scope = scope(Set.of(), Set.of(WIP_VIEW), OTHER_SHOP);

            assertThatThrownBy(() -> scope.require("PERM_" + WIP_VIEW, SHOP.toString()))
                    .isInstanceOf(LocationScopeDeniedException.class)
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                    .satisfies(ex -> {
                        LocationScopeDeniedException denied = (LocationScopeDeniedException) ex;
                        assertThat(denied.permission()).isEqualTo(WIP_VIEW);
                        assertThat(denied.locationId()).isEqualTo(SHOP.toString());
                        // The message names the permission, never the caller-supplied location.
                        assertThat(denied.getMessage()).contains(WIP_VIEW).doesNotContain(SHOP.toString());
                    });
            assertThat(LocationScopeDeniedException.ERROR_CODE).isEqualTo("LOCATION_SCOPE_DENIED");
        }

        @Test
        @DisplayName("the UUID overload behaves like the String one")
        void uuidOverload() {
            LocationScope scope = scope(Set.of(), Set.of(WIP_VIEW), OTHER_SHOP);

            assertThatThrownBy(() -> scope.require(WIP_VIEW, SHOP)).isInstanceOf(LocationScopeDeniedException.class);
        }
    }

    @Nested
    @DisplayName("a module that provides no LocationAncestorResolver bean")
    class MissingResolver {

        @Test
        @DisplayName("denies every scoped permission — never allows — and warns once")
        void deniesAndWarnsOnce() {
            Logger logger = (Logger) LoggerFactory.getLogger(LocationScope.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            LocationScope.resetMissingResolverWarningForTests();
            try {
                LocationScope scope =
                        LocationScope.of(Set.of(JE_POST), Set.of(WIP_VIEW), Optional.of(Set.of(SHOP)), true, null);

                // Directly assigned on both dimensions — would pass with a resolver.
                assertThat(scope.covers(WIP_VIEW, SHOP)).isFalse();
                assertThat(scope.covers(JE_POST, SHOP)).isFalse();
                // A global permission is unaffected: no resolver is needed to answer it.
                assertThat(scope.covers(GLOBAL_PERMISSION, SHOP)).isTrue();

                assertThat(appender.list)
                        .filteredOn(event -> event.getLevel() == Level.WARN)
                        .extracting(ILoggingEvent::getFormattedMessage)
                        .hasSize(1)
                        .first()
                        .asString()
                        .contains("no LocationAncestorResolver bean");
            } finally {
                logger.detachAppender(appender);
            }
        }
    }

    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("the sets are copied and immutable; toString never lists node ids")
        void immutableCopies() {
            Set<String> mutable = new java.util.HashSet<>(Set.of(WIP_VIEW));
            Set<UUID> mutableNodes = new java.util.HashSet<>(Set.of(SHOP));
            LocationScope scope = LocationScope.of(Set.of(), mutable, Optional.of(mutableNodes), true, RESOLVER);
            mutable.clear();
            mutableNodes.clear();

            assertThat(scope.otherScoped()).containsExactly(WIP_VIEW);
            assertThat(scope.financialScoped()).isEmpty();
            assertThat(scope.nodes()).contains(Set.of(SHOP));
            assertThatThrownBy(() -> scope.otherScoped().add("x")).isInstanceOf(UnsupportedOperationException.class);
            assertThat(scope.toString()).contains("claimsPresent=true").doesNotContain(SHOP.toString());
        }
    }

    @Nested
    @DisplayName("reach(P) — the narrowing accessor for an optional location filter")
    class ReachRows {

        @Test
        @DisplayName("claims absent → empty: a pre-rollout caller is not narrowed")
        void claimsAbsentIsEmpty() {
            assertThat(LocationScope.unscoped().reach(WIP_VIEW)).isEmpty();
            LocationScope flagged =
                    LocationScope.of(Set.of(WIP_VIEW), Set.of(), Optional.of(Set.of(SHOP)), false, RESOLVER);
            assertThat(flagged.reach(WIP_VIEW)).isEmpty();
        }

        @Test
        @DisplayName("P in neither bitset → empty: a global grant is not narrowed")
        void globalPermissionIsEmpty() {
            LocationScope scope = scope(Set.of(JE_POST), Set.of(WIP_VIEW), SHOP);

            assertThat(scope.reach(GLOBAL_PERMISSION)).isEmpty();
        }

        @Test
        @DisplayName("P scoped on one dimension → that dimension and the assigned nodes")
        void scopedOnOneDimension() {
            LocationScope scope = scope(Set.of(JE_POST), Set.of(WIP_VIEW), REGION, SHOP);

            assertThat(scope.reach(WIP_VIEW)).hasValueSatisfying(reach -> {
                assertThat(reach.dimensions()).containsExactly(Dimension.OTHER);
                assertThat(reach.nodes()).containsExactlyInAnyOrder(REGION, SHOP);
            });
            assertThat(scope.reach(JE_POST)).hasValueSatisfying(reach -> {
                assertThat(reach.dimensions()).containsExactly(Dimension.FINANCIAL);
                assertThat(reach.nodes()).containsExactlyInAnyOrder(REGION, SHOP);
            });
        }

        @Test
        @DisplayName("P scoped on both dimensions → both, so either rollup counts")
        void scopedOnBothDimensions() {
            LocationScope scope = scope(Set.of(WIP_VIEW), Set.of(WIP_VIEW), LEDGER);

            assertThat(scope.reach(WIP_VIEW)).hasValueSatisfying(reach -> {
                assertThat(reach.dimensions()).containsExactlyInAnyOrder(Dimension.FINANCIAL, Dimension.OTHER);
                assertThat(reach.nodes()).containsExactly(LEDGER);
            });
        }

        @Test
        @DisplayName("nodes absent → present with no nodes, so a caller narrows to nothing rather than to everything")
        void nodesAbsentIsPresentAndEmpty() {
            LocationScope scope = LocationScope.of(Set.of(), Set.of(WIP_VIEW), Optional.empty(), true, RESOLVER);

            assertThat(scope.reach(WIP_VIEW)).hasValueSatisfying(reach -> {
                assertThat(reach.dimensions()).containsExactly(Dimension.OTHER);
                assertThat(reach.nodes()).isEmpty();
            });
        }

        @Test
        @DisplayName("the PERM_ prefix is tolerated, and the resolver is never consulted")
        void prefixToleratedAndNoResolverNeeded() {
            LocationScope scope = LocationScope.of(Set.of(), Set.of(WIP_VIEW), Optional.of(Set.of(SHOP)), true, null);

            assertThat(scope.reach(GatewaySecurityConstants.PERMISSION_PREFIX + WIP_VIEW))
                    .hasValueSatisfying(reach -> assertThat(reach.nodes()).containsExactly(SHOP));
        }

        @Test
        @DisplayName("Reach is immutable and rejects an empty dimension set")
        void reachValueSemantics() {
            Set<Dimension> dims = java.util.EnumSet.of(Dimension.OTHER);
            Set<UUID> nodes = new java.util.HashSet<>(Set.of(SHOP));
            Reach reach = new Reach(dims, nodes);
            dims.clear();
            nodes.clear();

            assertThat(reach.dimensions()).containsExactly(Dimension.OTHER);
            assertThat(reach.nodes()).containsExactly(SHOP);
            assertThatThrownBy(() -> reach.nodes().add(REGION)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> new Reach(Set.of(), Set.of(SHOP))).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
