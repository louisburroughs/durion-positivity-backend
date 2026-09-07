package com.positivity.securityservice.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.enums.LocationHierarchy;
import com.positivity.securityservice.internal.enums.LocationScope;
import com.positivity.securityservice.internal.enums.PermissionCode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Composition rules of the two location-scope bitsets (ADR-0061 §2, #1868). */
@DisplayName("LocationScopeBits")
class LocationScopeBitsTest {

    private static final String JE_VIEW = PermissionCode.ACCOUNTING__JE__VIEW.code();
    private static final String ADJ_APPROVE = PermissionCode.INVENTORY__ADJUSTMENT__APPROVE.code();

    private static RoleGrant grant(
            String role, LocationScope scope, LocationHierarchy hierarchy, String... permissions) {
        return new RoleGrant(role, scope, hierarchy, Set.of(permissions));
    }

    @Test
    @DisplayName("a LOCATION role's grants land in the bitset of its hierarchy")
    void locationRole_landsInItsHierarchyBitset() {
        LocationScopeBits bits = LocationScopeBits.compose(List.of(
                grant("ACCOUNTANT", LocationScope.LOCATION, LocationHierarchy.FINANCIAL, JE_VIEW),
                grant("INVENTORY_MANAGER", LocationScope.LOCATION, LocationHierarchy.OTHER, ADJ_APPROVE)));

        assertThat(bits.financial()).containsExactly(PermissionCode.ACCOUNTING__JE__VIEW);
        assertThat(bits.other()).containsExactly(PermissionCode.INVENTORY__ADJUSTMENT__APPROVE);
        assertThat(bits.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("union semantics: a permission any ALL role grants is global and in neither bitset")
    void allRole_makesPermissionGlobal_broaderGrantWins() {
        LocationScopeBits bits = LocationScopeBits.compose(List.of(
                grant("INVENTORY_CONTROLLER", LocationScope.ALL, LocationHierarchy.OTHER, ADJ_APPROVE),
                grant("INVENTORY_MANAGER", LocationScope.LOCATION, LocationHierarchy.OTHER, ADJ_APPROVE, JE_VIEW)));

        assertThat(bits.other()).containsExactly(PermissionCode.ACCOUNTING__JE__VIEW);
        assertThat(bits.financial()).isEmpty();
    }

    @Test
    @DisplayName("a permission granted along both hierarchies is in both bitsets")
    void permissionGrantedBothWays_isInBothBitsets() {
        LocationScopeBits bits = LocationScopeBits.compose(List.of(
                grant("GENERAL_MANAGER", LocationScope.LOCATION, LocationHierarchy.FINANCIAL, JE_VIEW),
                grant("MANAGER", LocationScope.LOCATION, LocationHierarchy.OTHER, JE_VIEW)));

        assertThat(bits.financial()).containsExactly(PermissionCode.ACCOUNTING__JE__VIEW);
        assertThat(bits.other()).containsExactly(PermissionCode.ACCOUNTING__JE__VIEW);
    }

    @Test
    @DisplayName("ALL roles alone yield empty bitsets: today's behaviour")
    void onlyAllRoles_isEmpty() {
        LocationScopeBits bits = LocationScopeBits.compose(
                List.of(grant("ADMIN", LocationScope.ALL, LocationHierarchy.OTHER, JE_VIEW, ADJ_APPROVE)));

        assertThat(bits.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("no grants at all yields empty bitsets")
    void noGrants_isEmpty() {
        assertThat(LocationScopeBits.compose(List.of()).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a permission name outside the compiled catalog has no bit and is dropped, as perm_bits drops it")
    void unknownPermissionName_isDropped() {
        LocationScopeBits bits = LocationScopeBits.compose(List.of(
                grant("TECHNICIAN", LocationScope.LOCATION, LocationHierarchy.OTHER, "not:a:permission", JE_VIEW)));

        assertThat(bits.other()).containsExactly(PermissionCode.ACCOUNTING__JE__VIEW);
    }
}
