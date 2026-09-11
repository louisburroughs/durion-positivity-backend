package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.dto.RoleCreateRequest;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.exception.SecurityValidationException;
import com.positivity.securityservice.internal.repository.RoleRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link RoleManagementServiceImpl.TemplateRoleProvisioner#attempt} — the transactional single
 * attempt {@code provisionTemplateRole}'s retry loop calls (ADR-0062 §6/§7, plan WS8). This class
 * inherits the behavioural coverage {@code RoleManagementServiceImplTest} carried for {@code
 * provisionTemplateRole} before the retry fix (Copilot review of PR #1955, Finding 1) moved the
 * create/mark logic into this separate bean; only the outer retry loop stayed in
 * {@code RoleManagementServiceImplTest}.
 *
 * <p>Mocking {@link RoleRepository} here is legitimate: these tests pin {@code attempt}'s own
 * branching (existing vs. new, the platform-grant refusal), not Hibernate's flush timing — that is
 * what {@code TemplateRoleProvisioningConcurrencyTest} exists to prove against a real database.
 */
@ExtendWith(MockitoExtension.class)
class TemplateRoleProvisionerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private RolePersonaEventEmitter rolePersonaEventEmitter;

    private RoleManagementServiceImpl.TemplateRoleProvisioner provisioner;

    @BeforeEach
    void setUp() {
        provisioner = new RoleManagementServiceImpl.TemplateRoleProvisioner(
                roleRepository, TEST_CLOCK, rolePersonaEventEmitter);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        "platform-operator", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void cleanupSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** ADR-0062 §6 (WS8): a role loaded into the platform tenant joins the template under its own name. */
    @Test
    void attempt_createsTheRoleWithItsNameAsTemplateKey() {
        when(roleRepository.findByNameIgnoreCase("WARRANTY_CLERK")).thenReturn(Optional.empty());
        when(roleRepository.saveAndFlush(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Role created = provisioner.attempt(
                new RoleCreateRequest("WARRANTY_CLERK", "Warranty claim intake", null, null, null, null, null));

        assertThat(created.getTemplateKey()).isEqualTo("WARRANTY_CLERK");
        assertThat(created.getName()).isEqualTo("WARRANTY_CLERK");
        verify(rolePersonaEventEmitter).rolePersonaChanged(created);
    }

    @Test
    void attempt_marksAnExistingUnmarkedRoleAndLeavesTheRestAlone() {
        Role role = new Role();
        role.setId(UUID.fromString("00000000-0000-0000-0000-000000000007"));
        role.setName("SHOP_MANAGER");
        role.setDescription("as the platform wrote it");
        when(roleRepository.findByNameIgnoreCase("SHOP_MANAGER")).thenReturn(Optional.of(role));
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Role marked = provisioner.attempt(
                new RoleCreateRequest("SHOP_MANAGER", "a different description", null, null, null, null, null));

        assertThat(marked.getTemplateKey()).isEqualTo("SHOP_MANAGER");
        assertThat(role.getDescription()).isEqualTo("as the platform wrote it");
        verify(roleRepository).save(role);
        verify(rolePersonaEventEmitter, never()).rolePersonaChanged(any(Role.class));
    }

    /** Uniqueness is case-insensitive, so a differently-cased row is the same role: marked under its own name. */
    @Test
    void attempt_marksADifferentlyCasedExistingRoleUnderItsStoredName() {
        Role role = new Role();
        role.setId(UUID.fromString("00000000-0000-0000-0000-000000000009"));
        role.setName("Shop_Manager");
        when(roleRepository.findByNameIgnoreCase("SHOP_MANAGER")).thenReturn(Optional.of(role));
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Role marked = provisioner.attempt(new RoleCreateRequest("SHOP_MANAGER", null, null, null, null, null, null));

        assertThat(marked.getName()).isEqualTo("Shop_Manager");
        assertThat(marked.getTemplateKey()).isEqualTo("Shop_Manager");
    }

    @Test
    void attempt_isANoOpForARoleAlreadyInTheTemplate() {
        Role role = new Role();
        role.setId(UUID.fromString("00000000-0000-0000-0000-000000000008"));
        role.setName("ADMIN");
        role.setTemplateKey("ADMIN");
        when(roleRepository.findByNameIgnoreCase("ADMIN")).thenReturn(Optional.of(role));

        Role unchanged = provisioner.attempt(new RoleCreateRequest("ADMIN", null, null, null, null, null, null));

        assertThat(unchanged.getTemplateKey()).isEqualTo("ADMIN");
        verify(roleRepository, never()).save(any(Role.class));
        verify(roleRepository, never()).saveAndFlush(any(Role.class));
    }

    /**
     * Defense in depth for ADR-0062 §7's invariant: a role that already holds a {@code platform:*}
     * grant (the role-permission bulk/update paths, before {@link
     * com.positivity.securityservice.internal.domain.PlatformGrantGuard#refuseGrantingPlatformPermission}
     * existed to stop it there) must not be markable as a template role either.
     */
    @Test
    @DisplayName("attempt refuses to mark an existing role that already holds a platform:* permission")
    void attempt_refusesAnExistingRoleHoldingAPlatformPermission() {
        Role role = new Role();
        role.setId(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
        role.setName("ADMIN");
        Permission platformGrant = new Permission();
        platformGrant.setName("platform:tenant:create");
        role.getPermissions().add(platformGrant);
        when(roleRepository.findByNameIgnoreCase("ADMIN")).thenReturn(Optional.of(role));

        assertThatThrownBy(
                        () -> provisioner.attempt(new RoleCreateRequest("ADMIN", null, null, null, null, null, null)))
                .isInstanceOf(SecurityValidationException.class)
                .hasMessageContaining("platform:*");
        assertThat(role.getTemplateKey()).isNull();
        verify(roleRepository, never()).save(any(Role.class));
    }

    @Test
    @DisplayName("attempt lets a concurrent create's unique-index collision propagate to the caller")
    void attempt_propagatesACollisionInsteadOfCatchingItLocally() {
        when(roleRepository.findByNameIgnoreCase("WARRANTY_CLERK")).thenReturn(Optional.empty());
        DataIntegrityViolationException collision = new DataIntegrityViolationException("roles_tenant_lower_name_key");
        when(roleRepository.saveAndFlush(any(Role.class))).thenThrow(collision);

        assertThatThrownBy(() -> provisioner.attempt(
                        new RoleCreateRequest("WARRANTY_CLERK", null, null, null, null, null, null)))
                .isSameAs(collision);
        verify(rolePersonaEventEmitter, never()).rolePersonaChanged(any(Role.class));
    }
}
