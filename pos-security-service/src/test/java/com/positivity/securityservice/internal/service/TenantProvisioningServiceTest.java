package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.tenant.TenantProvisionedV1;
import com.positivity.securityservice.internal.config.OutboxEventWriter;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.enums.LocationHierarchy;
import com.positivity.securityservice.internal.enums.LocationScope;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class TenantProvisioningServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-000000000123");

    private final RoleRepository roles = mock(RoleRepository.class);
    private final PermissionRepository permissions = mock(PermissionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final UserService userService = mock(UserService.class);
    private final OutboxEventWriter outbox = mock(OutboxEventWriter.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OutboxEventWriter> outboxProvider = mock(ObjectProvider.class);

    private final TenantProvisioningService service = new TenantProvisioningService(
            roles,
            permissions,
            users,
            userService,
            outboxProvider,
            Clock.fixed(NOW, ZoneOffset.UTC),
            "tenant.events.v1");

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
        permission.setName(name);
        return permission;
    }

    @Test
    @DisplayName(
            "copies the template, creates the administrator on ADMIN awaiting activation, and answers tenant.provisioned")
    void provisionsAFreshTenant() {
        when(outboxProvider.getIfAvailable()).thenReturn(outbox);
        when(roles.existsByName(anyString())).thenReturn(false);
        when(permissions.findByName("security:role:view")).thenReturn(Optional.of(permission("security:role:view")));
        when(permissions.findByName("order:order:view")).thenReturn(Optional.of(permission("order:order:view")));
        when(permissions.findByName("not:registered:yet")).thenReturn(Optional.empty());
        when(users.existsByUsername("owner@acme.example")).thenReturn(false);

        TenantContext.bind(TENANT);
        TenantProvisioningService.Outcome outcome = service.provision(
                TENANT,
                "owner@acme.example",
                List.of(
                        entry("ADMIN", "security:role:view", "order:order:view", "not:registered:yet"),
                        entry("DISPATCHER", "order:order:view")));

        assertThat(outcome).isEqualTo(new TenantProvisioningService.Outcome(2, true));
        ArgumentCaptor<Role> saved = ArgumentCaptor.forClass(Role.class);
        verify(roles, org.mockito.Mockito.times(2)).save(saved.capture());
        Role admin = saved.getAllValues().get(0);
        assertThat(admin.getName()).isEqualTo("ADMIN");
        assertThat(admin.getTemplateKey()).isEqualTo("ADMIN");
        assertThat(admin.getCreatedBy()).isEqualTo(TenantProvisioningService.ACTOR);
        assertThat(admin.getCreatedAt()).isEqualTo(NOW);
        assertThat(admin.getLocationScope()).isEqualTo(LocationScope.LOCATION);
        assertThat(admin.getLocationHierarchy()).isEqualTo(LocationHierarchy.FINANCIAL);
        assertThat(admin.getPersonaTitle()).isEqualTo("persona ADMIN");
        assertThat(admin.getPermissions())
                .extracting(Permission::getName)
                .as("an unregistered permission is skipped, not fatal")
                .containsExactlyInAnyOrder("security:role:view", "order:order:view");
        // WS2b-3: the administrator cannot sign in until a platform operator mints an activation token
        verify(userService).createUserAwaitingActivation("owner@acme.example", Set.of("ADMIN"));
        verify(userService, never()).createUserWithGeneratedPassword(anyString(), any());
        verify(userService, never()).createUser(anyString(), anyString(), any());

        ArgumentCaptor<DomainEventEnvelope<?>> envelope = ArgumentCaptor.captor();
        verify(outbox).publish(eq("tenant.events.v1"), envelope.capture());
        assertThat(envelope.getValue().eventType()).isEqualTo(TenantProvisionedV1.EVENT_TYPE);
        assertThat(envelope.getValue().aggregateId()).isEqualTo(TENANT);
        assertThat(envelope.getValue().sourceService()).isEqualTo("pos-security-service");
        assertThat(envelope.getValue().payload()).isEqualTo(new TenantProvisionedV1(TENANT));
    }

    @Test
    @DisplayName("a redelivery leaves existing roles and the administrator alone but still answers")
    void redeliveryConverges() {
        when(outboxProvider.getIfAvailable()).thenReturn(outbox);
        when(roles.existsByName("ADMIN")).thenReturn(true);
        when(users.existsByUsername("owner@acme.example")).thenReturn(true);

        TenantContext.bind(TENANT);
        TenantProvisioningService.Outcome outcome =
                service.provision(TENANT, "owner@acme.example", List.of(entry("ADMIN", "security:role:view")));

        assertThat(outcome).isEqualTo(new TenantProvisioningService.Outcome(0, false));
        verify(roles, never()).save(any());
        verify(userService, never()).createUserAwaitingActivation(anyString(), any());
        verify(outbox).publish(eq("tenant.events.v1"), any());
    }

    @Test
    @DisplayName("refuses to run under another binding or without an ADMIN template role")
    void guards() {
        List<RoleTemplateEntry> template = List.of(entry("ADMIN"));
        TenantContext.bind(UUID.fromString("01990000-0000-7000-8000-000000000999"));
        assertThatThrownBy(() -> service.provision(TENANT, "owner@acme.example", template))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("own binding");

        TenantContext.bind(TENANT);
        List<RoleTemplateEntry> noAdmin = List.of(entry("DISPATCHER"));
        assertThatThrownBy(() -> service.provision(TENANT, "owner@acme.example", noAdmin))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN");
        verify(roles, never()).save(any());
    }

    @Test
    @DisplayName("without Kafka the rows are still written and the missing answer is logged")
    void noOutboxWhenKafkaIsOff() {
        when(outboxProvider.getIfAvailable()).thenReturn(null);
        when(roles.existsByName("ADMIN")).thenReturn(false);
        when(users.existsByUsername("owner@acme.example")).thenReturn(false);

        TenantContext.bind(TENANT);
        service.provision(TENANT, "owner@acme.example", List.of(entry("ADMIN")));

        verify(roles).save(any(Role.class));
        verify(userService).createUserAwaitingActivation("owner@acme.example", Set.of("ADMIN"));
    }
}
