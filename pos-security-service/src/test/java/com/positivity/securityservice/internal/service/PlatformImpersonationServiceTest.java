package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.config.AuditEventService;
import com.positivity.securityservice.internal.dto.AuditLogEventRequest;
import com.positivity.securityservice.internal.entity.ExtTenant;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.exception.PlatformTenantRequiredException;
import com.positivity.securityservice.internal.exception.TenantNotFoundException;
import com.positivity.securityservice.internal.exception.TenantNotImpersonableException;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.security.service.JwtService;
import com.positivity.securityservice.internal.security.service.JwtService.IssuedImpersonationToken;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Platform support access (ADR-0062 §7, WS2b-4): mint under the platform binding only, for an
 * ACTIVE tenant the replica knows that holds a SUPPORT role, as the authenticated operator, with
 * the token minted and stored under the target tenant's binding and audited on both sides.
 */
class PlatformImpersonationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-000000000123");
    private static final UUID OTHER_TENANT = UUID.fromString("01990000-0000-7000-8000-000000000999");
    private static final UUID OPERATOR_ID = UUID.fromString("01900000-0000-7000-8000-0000000a0101");
    private static final String OPERATOR = "admin.platform";
    private static final IssuedImpersonationToken ISSUED =
            new IssuedImpersonationToken("signed.jwt.token", "jti-1", NOW.plusSeconds(900), Set.of());

    private final ExtTenantRepository extTenants = mock(ExtTenantRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final AuditEventService audit = mock(AuditEventService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AuditEventService> auditProvider = mock(ObjectProvider.class);

    private final PlatformImpersonationService.BoundOperations bound =
            new PlatformImpersonationService.BoundOperations(users, roles, jwtService, auditProvider);
    private final PlatformImpersonationService service = new PlatformImpersonationService(extTenants, bound);

    @BeforeEach
    void operatorIsAuthenticated() {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        OPERATOR, null, List.of(new SimpleGrantedAuthority("platform:tenant:impersonate"))));
        User operator = new User();
        operator.setId(OPERATOR_ID);
        operator.setUsername(OPERATOR);
        when(users.findByUsername(OPERATOR)).thenReturn(Optional.of(operator));
        when(auditProvider.getIfAvailable()).thenReturn(audit);
        when(roles.existsByName("SUPPORT")).thenReturn(true);
        when(jwtService.generateImpersonationToken(anyString(), any(), anyString(), any()))
                .thenReturn(ISSUED);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static ExtTenant tenant(String status) {
        return ExtTenant.builder()
                .tenantId(TENANT)
                .slug("acme")
                .displayName("Acme Tire")
                .status(status)
                .aggregateVersion(3)
                .updatedAt(NOW)
                .build();
    }

    private void replicaHolds(String status) {
        when(extTenants.findById(TENANT)).thenReturn(Optional.of(tenant(status)));
    }

    @Test
    @DisplayName("refuses a caller bound to a tenant other than the platform tenant, and an unbound one")
    void requiresThePlatformBinding() {
        replicaHolds("ACTIVE");
        TenantContext.bind(OTHER_TENANT);
        assertThatThrownBy(() -> service.issue(TENANT, null))
                .isInstanceOf(PlatformTenantRequiredException.class)
                .hasMessageContaining(OTHER_TENANT.toString());

        TenantContext.clear();
        assertThatThrownBy(() -> service.issue(TENANT, null))
                .isInstanceOf(PlatformTenantRequiredException.class)
                .hasMessageContaining("none is bound");
        verify(jwtService, never()).generateImpersonationToken(anyString(), any(), anyString(), any());
        verify(audit, never()).createEvent(any());
    }

    @Test
    @DisplayName("a tenant the replica does not know → TenantNotFoundException (404)")
    void unknownTenantIs404() {
        TenantContext.bind(PlatformTenant.ID);
        when(extTenants.findById(TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(TENANT, null))
                .isInstanceOf(TenantNotFoundException.class)
                .hasMessageContaining(TENANT.toString());
        verify(jwtService, never()).generateImpersonationToken(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("a tenant that is not ACTIVE → TenantNotImpersonableException naming the status (409)")
    void inactiveTenantIs409() {
        TenantContext.bind(PlatformTenant.ID);
        replicaHolds("SUSPENDED");

        assertThatThrownBy(() -> service.issue(TENANT, null))
                .isInstanceOf(TenantNotImpersonableException.class)
                .hasMessageContaining("SUSPENDED");
        verify(jwtService, never()).generateImpersonationToken(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("the platform tenant itself cannot be impersonated (409)")
    void platformTenantIs409() {
        TenantContext.bind(PlatformTenant.ID);

        assertThatThrownBy(() -> service.issue(PlatformTenant.ID, null))
                .isInstanceOf(TenantNotImpersonableException.class)
                .hasMessageContaining("platform tenant");
        verify(extTenants, never()).findById(any());
    }

    @Test
    @DisplayName("a tenant without a SUPPORT role yet → TenantNotImpersonableException (409), nothing minted")
    void missingSupportRoleIs409() {
        TenantContext.bind(PlatformTenant.ID);
        replicaHolds("ACTIVE");
        when(roles.existsByName("SUPPORT")).thenReturn(false);

        assertThatThrownBy(() -> service.issue(TENANT, null))
                .isInstanceOf(TenantNotImpersonableException.class)
                .hasMessageContaining("SUPPORT");
        verify(jwtService, never()).generateImpersonationToken(anyString(), any(), anyString(), any());
        verify(audit, never()).createEvent(any());
    }

    @Test
    @DisplayName("an operator with no platform-tenant user row → UserNotFoundException")
    void operatorWithoutUserRowIsRefused() {
        TenantContext.bind(PlatformTenant.ID);
        replicaHolds("ACTIVE");
        when(users.findByUsername(OPERATOR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(TENANT, null)).isInstanceOf(UserNotFoundException.class);
        verify(jwtService, never()).generateImpersonationToken(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("mints under the target tenant's binding: synthetic subject, operator identity, SUPPORT role only")
    void mintsUnderTheTargetBinding() {
        TenantContext.bind(PlatformTenant.ID);
        replicaHolds("ACTIVE");
        AtomicReference<UUID> boundDuringMint = new AtomicReference<>();
        AtomicReference<UUID> boundDuringRoleCheck = new AtomicReference<>();
        when(roles.existsByName("SUPPORT")).thenAnswer(inv -> {
            boundDuringRoleCheck.set(TenantContext.require());
            return true;
        });
        when(jwtService.generateImpersonationToken(anyString(), any(), anyString(), any()))
                .thenAnswer(inv -> {
                    boundDuringMint.set(TenantContext.require());
                    return ISSUED;
                });

        PlatformImpersonationService.IssuedToken issued = service.issue(TENANT, "corr-1");

        assertThat(issued.token()).isEqualTo("signed.jwt.token");
        assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(issued.tenantId()).isEqualTo(TENANT);
        assertThat(issued.tenantSlug()).isEqualTo("acme");
        assertThat(boundDuringRoleCheck.get()).isEqualTo(TENANT);
        assertThat(boundDuringMint.get()).isEqualTo(TENANT);
        assertThat(TenantContext.require())
                .as("the platform binding is restored")
                .isEqualTo(PlatformTenant.ID);
        verify(jwtService)
                .generateImpersonationToken(
                        eq("support:admin.platform@acme"), eq(OPERATOR_ID), eq(OPERATOR), eq(Set.of("SUPPORT")));
    }

    @Test
    @DisplayName("audits the issuance in the target tenant and in the platform tenant, with the correlation id")
    void auditsOnBothSides() {
        TenantContext.bind(PlatformTenant.ID);
        replicaHolds("ACTIVE");
        AtomicReference<UUID> firstAuditBinding = new AtomicReference<>();
        AtomicReference<UUID> secondAuditBinding = new AtomicReference<>();
        when(audit.createEvent(any())).thenAnswer(inv -> {
            if (firstAuditBinding.get() == null) {
                firstAuditBinding.set(TenantContext.require());
            } else {
                secondAuditBinding.set(TenantContext.require());
            }
            return null;
        });

        service.issue(TENANT, "corr-ws2b-4");

        ArgumentCaptor<AuditLogEventRequest> captor = ArgumentCaptor.forClass(AuditLogEventRequest.class);
        verify(audit, times(2)).createEvent(captor.capture());
        assertThat(firstAuditBinding.get()).as("tenant-side event").isEqualTo(TENANT);
        assertThat(secondAuditBinding.get()).as("operator-side event").isEqualTo(PlatformTenant.ID);
        for (AuditLogEventRequest request : captor.getAllValues()) {
            assertThat(request.getEventType()).isEqualTo("PlatformImpersonationTokenIssued");
            assertThat(request.getEntityType()).isEqualTo("Tenant");
            assertThat(request.getEntityId()).isEqualTo(TENANT.toString());
            assertThat(request.getOldValue()).isEqualTo("");
            assertThat(request.getNewValue()).isEqualTo("expiresAt=" + NOW.plusSeconds(900));
            assertThat(request.getContext())
                    .isInstanceOf(Map.class)
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("operator", OPERATOR)
                    .containsEntry("subject", "support:admin.platform@acme")
                    .containsEntry("jti", "jti-1")
                    .containsEntry("expiresAt", NOW.plusSeconds(900).toString())
                    .containsEntry("role", "SUPPORT")
                    .containsEntry("correlationId", "corr-ws2b-4");
            assertThat(request.toString()).as("the token is never audited").doesNotContain("signed.jwt.token");
        }
    }

    @Test
    @DisplayName("grants the ceiling dropped are audited as droppedGrants and the token is still issued")
    void droppedGrantsAreAudited() {
        TenantContext.bind(PlatformTenant.ID);
        replicaHolds("ACTIVE");
        when(jwtService.generateImpersonationToken(anyString(), any(), anyString(), any()))
                .thenReturn(new IssuedImpersonationToken(
                        "signed.jwt.token", "jti-1", NOW.plusSeconds(900), Set.of("security:user:delete")));

        assertThat(service.issue(TENANT, null).token()).isEqualTo("signed.jwt.token");

        ArgumentCaptor<AuditLogEventRequest> captor = ArgumentCaptor.forClass(AuditLogEventRequest.class);
        verify(audit, times(2)).createEvent(captor.capture());
        for (AuditLogEventRequest request : captor.getAllValues()) {
            assertThat(request.getContext())
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("droppedGrants", List.of("security:user:delete"));
        }
    }

    @Test
    @DisplayName(
            "the synthetic subject never exceeds jwt_token.subject (255): the operator part is cut with a stable fingerprint")
    void syntheticSubjectIsBounded() {
        assertThat(PlatformImpersonationService.syntheticSubject("admin.platform", "acme"))
                .isEqualTo("support:admin.platform@acme");

        String longOperator = "o".repeat(255);
        String longSlug = "s".repeat(63);
        String subject = PlatformImpersonationService.syntheticSubject(longOperator, longSlug);
        assertThat(subject).hasSizeLessThanOrEqualTo(PlatformImpersonationService.MAX_SUBJECT_LENGTH);
        assertThat(subject)
                .hasSize(255)
                .startsWith("support:o")
                .endsWith("@" + longSlug)
                .contains("~");
        assertThat(PlatformImpersonationService.syntheticSubject(longOperator, longSlug))
                .as("deterministic across mints")
                .isEqualTo(subject);
        assertThat(PlatformImpersonationService.syntheticSubject("o".repeat(254) + "x", longSlug))
                .as("two long operators sharing a prefix get distinct subjects")
                .isNotEqualTo(subject)
                .hasSize(255);

        // Exactly at the limit: nothing is cut.
        String exact = "e".repeat(255 - "support:".length() - 1 - longSlug.length());
        assertThat(PlatformImpersonationService.syntheticSubject(exact, longSlug))
                .isEqualTo("support:" + exact + "@" + longSlug)
                .hasSize(255);
    }

    @Test
    @DisplayName("an audit failure or a missing audit service does not withhold the token")
    void auditFailureIsSwallowed() {
        TenantContext.bind(PlatformTenant.ID);
        replicaHolds("ACTIVE");
        when(audit.createEvent(any())).thenThrow(new IllegalStateException("audit store down"));
        assertThat(service.issue(TENANT, null).token()).isEqualTo("signed.jwt.token");

        when(auditProvider.getIfAvailable()).thenReturn(null);
        assertThat(service.issue(TENANT, null).token()).isEqualTo("signed.jwt.token");
    }
}
