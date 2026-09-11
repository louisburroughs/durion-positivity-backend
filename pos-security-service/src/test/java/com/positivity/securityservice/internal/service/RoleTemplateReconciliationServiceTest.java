package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.dto.RoleTemplateReconcileResponse;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.enums.LocationHierarchy;
import com.positivity.securityservice.internal.enums.LocationScope;
import com.positivity.securityservice.internal.exception.PlatformTenantRequiredException;
import com.positivity.securityservice.internal.exception.TenantNotFoundException;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@code reconcileTemplate(tenant)} (ADR-0062 §6, plan WS8): copies template roles the tenant
 * lacks, unions grants onto the ones it has, marks unmarked ones, never removes, is idempotent,
 * and is a platform-binding-only operation on a known tenant.
 */
@DisplayName("RoleTemplateReconciliationService")
class RoleTemplateReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");
    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-000000000123");

    private final RoleTemplateService templates = mock(RoleTemplateService.class);
    private final ExtTenantRepository tenants = mock(ExtTenantRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final PermissionRepository permissions = mock(PermissionRepository.class);

    private final RoleTemplateReconciliationService service = new RoleTemplateReconciliationService(
            templates,
            tenants,
            new RoleTemplateReconciliationService.BoundOperations(
                    roles, permissions, Clock.fixed(NOW, ZoneOffset.UTC)));

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static RoleTemplateEntry entry(String name, String... permissionNames) {
        return new RoleTemplateEntry(
                name,
                name,
                name + " from the template",
                "persona " + name,
                "focus",
                "tone",
                (short) 20,
                true,
                LocationScope.LOCATION,
                LocationHierarchy.FINANCIAL,
                Set.of(permissionNames));
    }

    private static Permission permission(String name) {
        Permission permission = new Permission();
        permission.setId(UUID.nameUUIDFromBytes(name.getBytes()));
        permission.setName(name);
        return permission;
    }

    private static Role existingRole(String name, String templateKey, String... permissionNames) {
        Role role = new Role();
        role.setId(UUID.nameUUIDFromBytes(("role-" + name).getBytes()));
        role.setName(name);
        role.setDescription("tenant's own wording");
        role.setTemplateKey(templateKey);
        role.setCreatedAt(NOW.minusSeconds(3600));
        role.setCreatedBy("tenant-admin");
        for (String permissionName : permissionNames) {
            role.getPermissions().add(permission(permissionName));
        }
        return role;
    }

    private void platformCaller() {
        TenantContext.bind(PlatformTenant.ID);
        when(tenants.existsById(TENANT)).thenReturn(true);
        when(roles.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));
        for (String name : List.of("crm:party:view", "warranty:claim:view", "order:view")) {
            when(permissions.findByName(name)).thenReturn(Optional.of(permission(name)));
        }
    }

    @Test
    @DisplayName("a template role the tenant lacks is copied, grants included, under the tenant's binding")
    void copiesMissingRoles() {
        platformCaller();
        when(templates.snapshot()).thenReturn(List.of(entry("WARRANTY_CLERK", "warranty:claim:view")));
        when(roles.findByNameIgnoreCase("WARRANTY_CLERK")).thenReturn(Optional.empty());
        AtomicReference<Optional<UUID>> boundDuringSave = new AtomicReference<>();
        when(roles.save(any(Role.class))).thenAnswer(invocation -> {
            boundDuringSave.set(TenantContext.current());
            return invocation.getArgument(0);
        });

        RoleTemplateReconcileResponse outcome = service.reconcile(TENANT);

        assertThat(outcome.tenantId()).isEqualTo(TENANT);
        assertThat(outcome.rolesCreated()).containsExactly("WARRANTY_CLERK");
        assertThat(outcome.grantsAdded()).isEmpty();
        assertThat(outcome.templateKeysAssigned()).isEmpty();
        ArgumentCaptor<Role> saved = ArgumentCaptor.forClass(Role.class);
        verify(roles).save(saved.capture());
        assertThat(saved.getValue().getTemplateKey()).isEqualTo("WARRANTY_CLERK");
        assertThat(saved.getValue().getDescription()).isEqualTo("WARRANTY_CLERK from the template");
        assertThat(saved.getValue().getLocationScope()).isEqualTo(LocationScope.LOCATION);
        assertThat(saved.getValue().getPermissions())
                .extracting(Permission::getName)
                .containsExactly("warranty:claim:view");
        assertThat(saved.getValue().getCreatedBy()).isEqualTo(RoleTemplateReconciliationService.ACTOR);
        verify(roles)
                .recordGrantProvenance(
                        any(),
                        eq(List.of(permission("warranty:claim:view").getId())),
                        eq(RoleTemplateReconciliationService.ACTOR),
                        eq(NOW));
        assertThat(boundDuringSave.get()).contains(TENANT);
        assertThat(TenantContext.current())
                .as("the platform binding is restored")
                .contains(PlatformTenant.ID);
    }

    @Test
    @DisplayName("an existing role keeps its own grants and wording and gains the template's new grants")
    void unionsGrantsOntoExistingRoles() {
        platformCaller();
        when(templates.snapshot()).thenReturn(List.of(entry("SHOP_MANAGER", "crm:party:view", "warranty:claim:view")));
        Role shopManager = existingRole("SHOP_MANAGER", "SHOP_MANAGER", "crm:party:view", "order:view");
        when(roles.findByNameIgnoreCase("SHOP_MANAGER")).thenReturn(Optional.of(shopManager));

        RoleTemplateReconcileResponse outcome = service.reconcile(TENANT);

        assertThat(outcome.rolesCreated()).isEmpty();
        assertThat(outcome.grantsAdded())
                .containsExactly(new RoleTemplateReconcileResponse.GrantAdded("SHOP_MANAGER", "warranty:claim:view"));
        assertThat(outcome.templateKeysAssigned()).isEmpty();
        assertThat(shopManager.getPermissions())
                .extracting(Permission::getName)
                .as("union: the tenant-local order:view survives, the template's grant is added")
                .containsExactlyInAnyOrder("crm:party:view", "order:view", "warranty:claim:view");
        assertThat(shopManager.getDescription()).isEqualTo("tenant's own wording");
        assertThat(shopManager.getLastModifiedBy()).isEqualTo(RoleTemplateReconciliationService.ACTOR);
        verify(roles).save(shopManager);
        verify(roles)
                .recordGrantProvenance(
                        eq(shopManager.getId()),
                        eq(List.of(permission("warranty:claim:view").getId())),
                        eq(RoleTemplateReconciliationService.ACTOR),
                        eq(NOW));
    }

    @Test
    @DisplayName("an existing role without a template key is marked; a template grant the catalog lacks is skipped")
    void marksUnmarkedRolesAndSkipsUnknownGrants() {
        platformCaller();
        when(templates.snapshot()).thenReturn(List.of(entry("DISPATCHER", "crm:party:view", "not:registered:yet")));
        Role dispatcher = existingRole("DISPATCHER", null, "crm:party:view");
        when(roles.findByNameIgnoreCase("DISPATCHER")).thenReturn(Optional.of(dispatcher));
        when(permissions.findByName("not:registered:yet")).thenReturn(Optional.empty());

        RoleTemplateReconcileResponse outcome = service.reconcile(TENANT);

        assertThat(outcome.templateKeysAssigned()).containsExactly("DISPATCHER");
        assertThat(outcome.grantsAdded()).isEmpty();
        assertThat(dispatcher.getTemplateKey()).isEqualTo("DISPATCHER");
        verify(roles).save(dispatcher);
        verify(roles, never()).recordGrantProvenance(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a differently-cased tenant role is the template role, matched case-insensitively and kept as named")
    void matchesExistingRolesCaseInsensitively() {
        platformCaller();
        when(templates.snapshot()).thenReturn(List.of(entry("SHOP_MANAGER", "crm:party:view")));
        Role shopManager = existingRole("shop_manager", null, "crm:party:view");
        when(roles.findByNameIgnoreCase("SHOP_MANAGER")).thenReturn(Optional.of(shopManager));

        RoleTemplateReconcileResponse outcome = service.reconcile(TENANT);

        assertThat(outcome.rolesCreated()).as("no duplicate is created").isEmpty();
        assertThat(outcome.templateKeysAssigned()).containsExactly("shop_manager");
        assertThat(shopManager.getName()).as("the tenant's stored name stays").isEqualTo("shop_manager");
        assertThat(shopManager.getTemplateKey())
                .as("the template's canonical name is the key")
                .isEqualTo("SHOP_MANAGER");
    }

    @Test
    @DisplayName("a unique-key collision with a concurrent reconciliation is retried and converges")
    void retriesOnAConcurrentInsert() {
        RoleTemplateReconciliationService.BoundOperations racing =
                mock(RoleTemplateReconciliationService.BoundOperations.class);
        RoleTemplateReconciliationService racingService =
                new RoleTemplateReconciliationService(templates, tenants, racing);
        TenantContext.bind(PlatformTenant.ID);
        when(tenants.existsById(TENANT)).thenReturn(true);
        List<RoleTemplateEntry> template = List.of(entry("WARRANTY_CLERK", "warranty:claim:view"));
        when(templates.snapshot()).thenReturn(template);
        RoleTemplateReconcileResponse converged =
                new RoleTemplateReconcileResponse(TENANT, List.of(), List.of(), List.of());
        when(racing.apply(TENANT, template))
                .thenThrow(new DataIntegrityViolationException("roles_tenant_id_name_key"))
                .thenReturn(converged);

        assertThat(racingService.reconcile(TENANT)).isSameAs(converged);
        verify(racing, times(2)).apply(TENANT, template);
    }

    @Test
    @DisplayName("a collision that persists is given up on after the last attempt")
    void givesUpAfterTheLastAttempt() {
        RoleTemplateReconciliationService.BoundOperations racing =
                mock(RoleTemplateReconciliationService.BoundOperations.class);
        RoleTemplateReconciliationService racingService =
                new RoleTemplateReconciliationService(templates, tenants, racing);
        TenantContext.bind(PlatformTenant.ID);
        when(tenants.existsById(TENANT)).thenReturn(true);
        when(templates.snapshot()).thenReturn(List.of());
        when(racing.apply(eq(TENANT), any())).thenThrow(new DataIntegrityViolationException("still colliding"));

        assertThatThrownBy(() -> racingService.reconcile(TENANT)).isInstanceOf(DataIntegrityViolationException.class);
        verify(racing, times(RoleTemplateReconciliationService.MAX_ATTEMPTS)).apply(eq(TENANT), any());
    }

    @Test
    @DisplayName("a tenant already up to the template is untouched: nothing saved, empty lists")
    void isIdempotent() {
        platformCaller();
        when(templates.snapshot()).thenReturn(List.of(entry("SHOP_MANAGER", "crm:party:view")));
        Role shopManager = existingRole("SHOP_MANAGER", "SHOP_MANAGER", "crm:party:view");
        when(roles.findByNameIgnoreCase("SHOP_MANAGER")).thenReturn(Optional.of(shopManager));

        RoleTemplateReconcileResponse outcome = service.reconcile(TENANT);

        assertThat(outcome.rolesCreated()).isEmpty();
        assertThat(outcome.grantsAdded()).isEmpty();
        assertThat(outcome.templateKeysAssigned()).isEmpty();
        verify(roles, never()).save(any());
        verify(roles, never()).recordGrantProvenance(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the platform tenant itself may be reconciled (a no-op) without an ext_tenant row")
    void thePlatformTenantNeedsNoRegistryRow() {
        platformCaller();
        when(templates.snapshot()).thenReturn(new ArrayList<>());

        RoleTemplateReconcileResponse outcome = service.reconcile(PlatformTenant.ID);

        assertThat(outcome.rolesCreated()).isEmpty();
        verify(tenants, never()).existsById(any());
    }

    @Test
    @DisplayName("a caller bound to a tenant other than the platform tenant is refused, whatever it holds")
    void refusesANonPlatformBinding() {
        TenantContext.bind(TENANT);

        assertThatThrownBy(() -> service.reconcile(TENANT)).isInstanceOf(PlatformTenantRequiredException.class);
        verify(templates, never()).snapshot();
        verify(roles, never()).save(any());
    }

    @Test
    @DisplayName("an unbound caller is refused too")
    void refusesAnUnboundCaller() {
        assertThatThrownBy(() -> service.reconcile(TENANT)).isInstanceOf(PlatformTenantRequiredException.class);
    }

    @Test
    @DisplayName("a tenant the ext_tenant replica does not hold is 404")
    void refusesAnUnknownTenant() {
        TenantContext.bind(PlatformTenant.ID);
        when(tenants.existsById(TENANT)).thenReturn(false);

        assertThatThrownBy(() -> service.reconcile(TENANT)).isInstanceOf(TenantNotFoundException.class);
        verify(templates, never()).snapshot();
    }
}
