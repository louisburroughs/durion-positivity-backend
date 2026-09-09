package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.event.RoleAssignmentRevokedEvent;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * ADR-0061 amendment phase 2 (#1914): {@link UserRoleGrantServiceImpl} is the only writer of
 * {@code role_assignments} across the module's provisioning paths, so its grant / revoke /
 * reconcile semantics are covered directly here rather than only through each caller.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserRoleGrantServiceImpl")
class UserRoleGrantServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final Clock TEST_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ACTOR = "test-actor";

    @Mock
    private RoleAssignmentRepository roleAssignmentRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private UserRoleGrantServiceImpl sut;

    private User user;
    private Role roleA;
    private Role roleB;

    @BeforeEach
    void setUp() {
        sut = new UserRoleGrantServiceImpl(roleAssignmentRepository, TEST_CLOCK, eventPublisher);
        user = new User();
        user.setId(UUID.randomUUID());
        roleA = new Role();
        roleA.setId(UUID.randomUUID());
        roleA.setName("ROLE_A");
        roleB = new Role();
        roleB.setId(UUID.randomUUID());
        roleB.setName("ROLE_B");
    }

    @Test
    @DisplayName("grant() creates an open-ended assignment when the user does not already effectively hold the role")
    void grant_noExistingAssignment_createsOpenEndedAssignment() {
        when(roleAssignmentRepository.findByUserAndRole(user, roleA)).thenReturn(List.of());

        sut.grant(user, roleA, ACTOR);

        ArgumentCaptor<RoleAssignment> captor = ArgumentCaptor.forClass(RoleAssignment.class);
        verify(roleAssignmentRepository).save(captor.capture());
        RoleAssignment saved = captor.getValue();
        assertThat(saved.getUser()).isEqualTo(user);
        assertThat(saved.getRole()).isEqualTo(roleA);
        assertThat(saved.getEffectiveStartDate()).isEqualTo(LocalDateTime.ofInstant(NOW, TEST_CLOCK.getZone()));
        assertThat(saved.getEffectiveEndDate()).isNull();
        assertThat(saved.getCreatedBy()).isEqualTo(ACTOR);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        // Grants are not security-critical the way revocations are (ADR-0061 §4 amendment,
        // #1914 phase 3 explicitly names revocation only): no token revocation event.
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("grant() is a no-op when an effective assignment for the pair already exists")
    void grant_alreadyEffective_isNoOp() {
        RoleAssignment existing =
                openEndedAssignment(roleA, LocalDateTime.now(TEST_CLOCK).minusDays(1));
        when(roleAssignmentRepository.findByUserAndRole(user, roleA)).thenReturn(List.of(existing));

        sut.grant(user, roleA, ACTOR);

        verify(roleAssignmentRepository, never()).save(any(RoleAssignment.class));
    }

    @Test
    @DisplayName("grant() grants when the only existing assignment for the pair is not currently effective")
    void grant_onlyExpiredAssignment_createsNewOne() {
        RoleAssignment expired = new RoleAssignment();
        expired.setUser(user);
        expired.setRole(roleA);
        expired.setEffectiveStartDate(LocalDateTime.now(TEST_CLOCK).minusYears(1));
        expired.setEffectiveEndDate(LocalDateTime.now(TEST_CLOCK).minusDays(1));
        when(roleAssignmentRepository.findByUserAndRole(user, roleA)).thenReturn(List.of(expired));

        sut.grant(user, roleA, ACTOR);

        verify(roleAssignmentRepository).save(any(RoleAssignment.class));
    }

    @Test
    @DisplayName("revoke() ends the effective assignment's window and stamps revokedAt")
    void revoke_effectiveAssignment_endsWindowAndStampsRevokedAt() {
        RoleAssignment existing =
                openEndedAssignment(roleA, LocalDateTime.now(TEST_CLOCK).minusDays(1));
        when(roleAssignmentRepository.findByUserAndRole(user, roleA)).thenReturn(List.of(existing));

        sut.revoke(user, roleA, ACTOR);

        assertThat(existing.getEffectiveEndDate()).isEqualTo(LocalDateTime.ofInstant(NOW, TEST_CLOCK.getZone()));
        assertThat(existing.getRevokedAt()).isEqualTo(NOW);
        assertThat(existing.getLastModifiedBy()).isEqualTo(ACTOR);
        verify(roleAssignmentRepository).save(existing);
        // ADR-0061 §4 amendment (#1914 phase 3): revocation ends the holder's live tokens, via
        // RoleAssignmentRevokedEvent rather than a direct call (see that type's javadoc).
        ArgumentCaptor<RoleAssignmentRevokedEvent> eventCaptor =
                ArgumentCaptor.forClass(RoleAssignmentRevokedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getUserId()).isEqualTo(user.getId());
    }

    @Test
    @DisplayName("revoke() is a no-op when the user holds no effective assignment for the role")
    void revoke_noEffectiveAssignment_isNoOp() {
        when(roleAssignmentRepository.findByUserAndRole(user, roleA)).thenReturn(List.of());

        sut.revoke(user, roleA, ACTOR);

        verify(roleAssignmentRepository, never()).save(any(RoleAssignment.class));
        // A no-op revoke must not tell the token layer to revoke anything.
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("reconcile() grants a desired role not already effectively held")
    void reconcile_grantsMissingRole() {
        when(roleAssignmentRepository.findByUser(user)).thenReturn(List.of());
        when(roleAssignmentRepository.findByUserAndRole(user, roleA)).thenReturn(List.of());

        sut.reconcile(user, Set.of(roleA), ACTOR);

        ArgumentCaptor<RoleAssignment> captor = ArgumentCaptor.forClass(RoleAssignment.class);
        verify(roleAssignmentRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(roleA);
        // Reconcile granting only, nothing revoked: no token revocation event.
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("reconcile() revokes an effectively held role not in the desired set")
    void reconcile_revokesRoleNotDesired() {
        RoleAssignment existing =
                openEndedAssignment(roleA, LocalDateTime.now(TEST_CLOCK).minusDays(1));
        when(roleAssignmentRepository.findByUser(user)).thenReturn(List.of(existing));

        sut.reconcile(user, Set.of(), ACTOR);

        assertThat(existing.getEffectiveEndDate()).isEqualTo(LocalDateTime.ofInstant(NOW, TEST_CLOCK.getZone()));
        assertThat(existing.getRevokedAt()).isEqualTo(NOW);
        verify(roleAssignmentRepository).save(existing);
        ArgumentCaptor<RoleAssignmentRevokedEvent> eventCaptor =
                ArgumentCaptor.forClass(RoleAssignmentRevokedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getUserId()).isEqualTo(user.getId());
    }

    @Test
    @DisplayName("reconcile() grants missing roles and revokes absent ones in the same call")
    void reconcile_grantsMissingAndRevokesAbsent() {
        RoleAssignment existingA =
                openEndedAssignment(roleA, LocalDateTime.now(TEST_CLOCK).minusDays(1));
        when(roleAssignmentRepository.findByUser(user)).thenReturn(List.of(existingA));
        when(roleAssignmentRepository.findByUserAndRole(user, roleB)).thenReturn(List.of());

        sut.reconcile(user, Set.of(roleB), ACTOR);

        // roleA revoked, roleB granted: two distinct save() calls total.
        assertThat(existingA.getEffectiveEndDate()).isEqualTo(LocalDateTime.ofInstant(NOW, TEST_CLOCK.getZone()));
        ArgumentCaptor<RoleAssignment> captor = ArgumentCaptor.forClass(RoleAssignment.class);
        verify(roleAssignmentRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).contains(existingA);
        assertThat(captor.getAllValues())
                .anySatisfy(a -> assertThat(a.getRole()).isEqualTo(roleB));
        // Exactly one event for the one user, even though one assignment was revoked and another
        // granted in the same call.
        ArgumentCaptor<RoleAssignmentRevokedEvent> eventCaptor =
                ArgumentCaptor.forClass(RoleAssignmentRevokedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getUserId()).isEqualTo(user.getId());
    }

    @Test
    @DisplayName("reconcile() is a no-op when the desired set already matches the effective set")
    void reconcile_desiredMatchesEffective_isNoOp() {
        RoleAssignment existing =
                openEndedAssignment(roleA, LocalDateTime.now(TEST_CLOCK).minusDays(1));
        when(roleAssignmentRepository.findByUser(user)).thenReturn(List.of(existing));

        sut.reconcile(user, Set.of(roleA), ACTOR);

        verify(roleAssignmentRepository, never()).save(any(RoleAssignment.class));
        verifyNoInteractions(eventPublisher);
    }

    private RoleAssignment openEndedAssignment(Role role, LocalDateTime start) {
        RoleAssignment assignment = new RoleAssignment();
        assignment.setUser(user);
        assignment.setRole(role);
        assignment.setEffectiveStartDate(start);
        return assignment;
    }
}
