package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.domain.RoleGrant;
import com.positivity.securityservice.internal.dto.RoleGrantRow;
import com.positivity.securityservice.internal.enums.LocationHierarchy;
import com.positivity.securityservice.internal.enums.LocationScope;
import com.positivity.securityservice.internal.repository.RoleRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Role expansion reads persisted {@code role_permissions} grants; there is no compiled
 * role-to-authority map any more. These tests pin the resolution contract — normalization,
 * the {@code ROLE_} authority, and fail-closed behaviour — independently of what the seed
 * baseline happens to grant. Which permissions each canonical role actually receives is
 * asserted against the seed in {@code RolePermissionBaselineTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoleAuthorityService")
class RoleAuthorityServiceTest {

    @Mock
    private RoleRepository roleRepository;

    private RoleAuthorityServiceImpl service() {
        return new RoleAuthorityServiceImpl(roleRepository);
    }

    @Test
    @DisplayName("returns the ROLE_ authority plus the role's persisted grants")
    void expandRolesToAuthorities_returnsRolePrefixAndPersistedGrants() {
        when(roleRepository.findPermissionNamesByRoleNames(anyCollection()))
                .thenReturn(Set.of("crm:party:view", "crm:party:search"));

        assertThat(service().expandRolesToAuthorities(Set.of("CSR")))
                .containsExactlyInAnyOrder("ROLE_CSR", "crm:party:view", "crm:party:search");
    }

    @Test
    @DisplayName("unions grants across every role the principal holds")
    void expandRolesToAuthorities_unionsGrantsAcrossRoles() {
        when(roleRepository.findPermissionNamesByRoleNames(anyCollection()))
                .thenReturn(Set.of("accounting:je:view", "workorder:workorder:view"));

        Set<String> authorities = service().expandRolesToAuthorities(Set.of("ACCOUNTING_ASSOCIATE", "TECHNICIAN"));

        assertThat(authorities)
                .containsExactlyInAnyOrder(
                        "ROLE_ACCOUNTING_ASSOCIATE",
                        "ROLE_TECHNICIAN",
                        "accounting:je:view",
                        "workorder:workorder:view");

        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.captor();
        verify(roleRepository).findPermissionNamesByRoleNames(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder("ACCOUNTING_ASSOCIATE", "TECHNICIAN");
    }

    @Test
    @DisplayName("normalizes case and strips an existing ROLE_ prefix before lookup")
    void expandRolesToAuthorities_normalizesRoleNamesBeforeLookup() {
        when(roleRepository.findPermissionNamesByRoleNames(anyCollection())).thenReturn(Set.of());

        assertThat(service().expandRolesToAuthorities(Set.of("ROLE_dispatcher", "  technician  ")))
                .containsExactlyInAnyOrder("ROLE_DISPATCHER", "ROLE_TECHNICIAN");

        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.captor();
        verify(roleRepository).findPermissionNamesByRoleNames(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder("DISPATCHER", "TECHNICIAN");
    }

    @Test
    @DisplayName("fails closed: a role with no grants carries only its ROLE_ authority")
    void expandRolesToAuthorities_roleWithoutGrants_yieldsOnlyRoleAuthority() {
        when(roleRepository.findPermissionNamesByRoleNames(anyCollection())).thenReturn(Set.of());

        assertThat(service().expandRolesToAuthorities(Set.of("SELF_SERVICE_CUSTOMER")))
                .containsExactly("ROLE_SELF_SERVICE_CUSTOMER");
    }

    @Test
    @DisplayName("fails closed: an unknown role grants no permissions")
    void expandRolesToAuthorities_unknownRole_grantsNoPermissions() {
        when(roleRepository.findPermissionNamesByRoleNames(anyCollection())).thenReturn(Set.of());

        assertThat(service().expandRolesToAuthorities(Set.of("MECHANIC")))
                .containsExactly("ROLE_MECHANIC")
                .noneMatch(authority -> authority.contains(":"));
    }

    @Test
    @DisplayName("null role set yields no authorities and no lookup")
    void expandRolesToAuthorities_nullRoles_returnsEmpty() {
        assertThat(service().expandRolesToAuthorities(null)).isEmpty();
        verify(roleRepository, never()).findPermissionNamesByRoleNames(anyCollection());
    }

    @Test
    @DisplayName("empty role set yields no authorities and no lookup")
    void expandRolesToAuthorities_emptyRoles_returnsEmpty() {
        assertThat(service().expandRolesToAuthorities(Set.of())).isEmpty();
        verify(roleRepository, never()).findPermissionNamesByRoleNames(anyCollection());
    }

    @Test
    @DisplayName("blank and null entries are skipped without a lookup")
    void expandRolesToAuthorities_blankAndNullEntries_areSkipped() {
        Set<String> roles = new HashSet<>();
        roles.add(null);
        roles.add(" ");

        assertThat(service().expandRolesToAuthorities(roles)).isEmpty();
        verify(roleRepository, never()).findPermissionNamesByRoleNames(anyCollection());
    }

    // ---------------------------------------------------------------------------------------
    // resolveRoleGrants (ADR-0061 §2, #1868): the per-role, scope-carrying form of the same grants
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("resolveRoleGrants groups rows per role, carrying that role's scope and hierarchy")
    void resolveRoleGrants_groupsRowsPerRoleWithScope() {
        when(roleRepository.findGrantRowsByRoleNames(anyCollection()))
                .thenReturn(List.of(
                        new RoleGrantRow(
                                "INVENTORY_MANAGER", LocationScope.LOCATION, LocationHierarchy.OTHER, "inv:adj:view"),
                        new RoleGrantRow(
                                "ACCOUNTANT", LocationScope.LOCATION, LocationHierarchy.FINANCIAL, "acc:je:view"),
                        new RoleGrantRow(
                                "INVENTORY_MANAGER",
                                LocationScope.LOCATION,
                                LocationHierarchy.OTHER,
                                "inv:adj:approve")));

        List<RoleGrant> grants = service().resolveRoleGrants(Set.of("INVENTORY_MANAGER", "ACCOUNTANT"));

        assertThat(grants)
                .containsExactly(
                        new RoleGrant(
                                "ACCOUNTANT",
                                LocationScope.LOCATION,
                                LocationHierarchy.FINANCIAL,
                                Set.of("acc:je:view")),
                        new RoleGrant(
                                "INVENTORY_MANAGER",
                                LocationScope.LOCATION,
                                LocationHierarchy.OTHER,
                                Set.of("inv:adj:view", "inv:adj:approve")));
    }

    @Test
    @DisplayName("resolveRoleGrants normalizes names before lookup and keys the result by the normalized name")
    void resolveRoleGrants_normalizesRoleNames() {
        when(roleRepository.findGrantRowsByRoleNames(anyCollection()))
                .thenReturn(List.of(
                        new RoleGrantRow("Dispatcher", LocationScope.LOCATION, LocationHierarchy.OTHER, "x:y:z")));

        List<RoleGrant> grants = service().resolveRoleGrants(Set.of("ROLE_dispatcher", "  technician  "));

        assertThat(grants).extracting(RoleGrant::roleName).containsExactly("DISPATCHER");

        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.captor();
        verify(roleRepository).findGrantRowsByRoleNames(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder("DISPATCHER", "TECHNICIAN");
    }

    @Test
    @DisplayName(
            "resolveRoleGrants fails closed: unknown or ungranted roles contribute no entry and no ROLE_ authority")
    void resolveRoleGrants_ungrantedRole_contributesNothing() {
        when(roleRepository.findGrantRowsByRoleNames(anyCollection())).thenReturn(List.of());

        assertThat(service().resolveRoleGrants(Set.of("MECHANIC", "SELF_SERVICE_CUSTOMER")))
                .isEmpty();
    }

    @Test
    @DisplayName("resolveRoleGrants with null, empty, or blank-only roles does no lookup")
    void resolveRoleGrants_noUsableRoles_noLookup() {
        Set<String> blanks = new HashSet<>();
        blanks.add(null);
        blanks.add(" ");

        assertThat(service().resolveRoleGrants(null)).isEmpty();
        assertThat(service().resolveRoleGrants(Set.of())).isEmpty();
        assertThat(service().resolveRoleGrants(blanks)).isEmpty();
        verify(roleRepository, never()).findGrantRowsByRoleNames(anyCollection());
    }
}
