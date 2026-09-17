package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.LocationAncestry.Dimension;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Who may manage and who may see a work session (issue #2061 BR4, #85 OQ2): the person
 * themself, or a supervisor whose timekeeping grant covers the person's location.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkSessionAccessPolicy")
class WorkSessionAccessPolicyTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-02-16T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.now(CLOCK);
    private static final String ADA_USER = "ada";
    private static final String MANAGER_USER = "manager";
    private static final UUID ADA = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID GRACE = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID SHOP_A = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");
    private static final UUID SHOP_B = UUID.fromString("018f0000-0000-7000-8000-0000000000b1");

    private static final LocationAncestorResolver RESOLVER = LocationScopeFixtures.resolverOf(Map.of(
            SHOP_A, LocationScopeFixtures.selfOnly(SHOP_A),
            SHOP_B, LocationScopeFixtures.selfOnly(SHOP_B)));

    @Mock
    private UserPersonTranslationService userPersonTranslationService;

    @Mock
    private EmployeeLocationAssignmentRepository assignmentRepository;

    private WorkSessionAccessPolicy policy() {
        return new WorkSessionAccessPolicy(userPersonTranslationService, assignmentRepository, CLOCK);
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    /** Authenticates a caller with the given authorities; a null scope means a pre-rollout token. */
    private static void caller(String username, LocationScope scope, String... authorities) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(username, null, authorities);
        Map<String, Object> details = new java.util.HashMap<>();
        details.put(GatewaySecurityConstants.DETAIL_USERNAME, username);
        if (scope != null) {
            details.put(GatewaySecurityConstants.DETAIL_LOCATION_SCOPE, scope);
        }
        authentication.setDetails(details);
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static LocationScope scopedOn(String permission, UUID... nodes) {
        return LocationScopeFixtures.scopedOn(permission, Set.of(Dimension.OTHER), RESOLVER, nodes);
    }

    private static EmployeeLocationAssignment assignmentAt(UUID locationId) {
        EmployeeLocationAssignment assignment = new EmployeeLocationAssignment();
        assignment.setLocationId(locationId);
        return assignment;
    }

    private void linked(String username, UUID personId) {
        when(userPersonTranslationService.findPersonUuidForUser(username)).thenReturn(Optional.of(personId));
    }

    private void unlinked(String username) {
        when(userPersonTranslationService.findPersonUuidForUser(username)).thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("requireMayManage — clock in/out, breaks, submit")
    class Manage {

        @Test
        @DisplayName("the person themself may, with no supervisory permission at all")
        void selfMayManage() {
            linked(ADA_USER, ADA);
            caller(ADA_USER, null);

            assertThatCode(() -> policy().requireMayManage(ADA)).doesNotThrowAnyException();
            verify(assignmentRepository, never()).findActiveByPersonIdAndDate(any(), any());
        }

        @Test
        @DisplayName("an authenticated user who is neither the person nor an approver is refused")
        void strangerIsRefused() {
            linked(ADA_USER, ADA);
            caller(ADA_USER, null, "workorder:workorder:view");

            assertThatThrownBy(() -> policy().requireMayManage(GRACE)).isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("an unlinked caller is never 'self'")
        void unlinkedCallerIsNotSelf() {
            unlinked(MANAGER_USER);
            caller(MANAGER_USER, null);

            assertThatThrownBy(() -> policy().requireMayManage(ADA)).isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("an unscoped people:timekeeping:approve holder may manage anyone, with no assignment lookup")
        void globalApproverMayManage() {
            unlinked(MANAGER_USER);
            caller(
                    MANAGER_USER,
                    LocationScopeFixtures.globalWithClaims(RESOLVER),
                    PeoplePermissions.TIMEKEEPING_APPROVE);

            assertThatCode(() -> policy().requireMayManage(ADA)).doesNotThrowAnyException();
            verify(assignmentRepository, never()).findActiveByPersonIdAndDate(any(), any());
        }

        @Test
        @DisplayName("a pre-rollout token (no scope claims) behaves as an unscoped grant")
        void preRolloutApproverMayManage() {
            unlinked(MANAGER_USER);
            caller(MANAGER_USER, null, PeoplePermissions.TIMEKEEPING_APPROVE);

            assertThatCode(() -> policy().requireMayManage(ADA)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a location-scoped approver may manage a person assigned within their reach")
        void scopedApproverInReach() {
            unlinked(MANAGER_USER);
            when(assignmentRepository.findActiveByPersonIdAndDate(ADA, TODAY))
                    .thenReturn(List.of(assignmentAt(SHOP_B), assignmentAt(SHOP_A)));
            caller(
                    MANAGER_USER,
                    scopedOn(PeoplePermissions.TIMEKEEPING_APPROVE, SHOP_A),
                    PeoplePermissions.TIMEKEEPING_APPROVE);

            assertThatCode(() -> policy().requireMayManage(ADA)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a location-scoped approver is refused for a person assigned outside their reach")
        void scopedApproverOutOfReach() {
            unlinked(MANAGER_USER);
            when(assignmentRepository.findActiveByPersonIdAndDate(ADA, TODAY))
                    .thenReturn(List.of(assignmentAt(SHOP_B)));
            caller(
                    MANAGER_USER,
                    scopedOn(PeoplePermissions.TIMEKEEPING_APPROVE, SHOP_A),
                    PeoplePermissions.TIMEKEEPING_APPROVE);

            assertThatThrownBy(() -> policy().requireMayManage(ADA))
                    .isInstanceOf(LocationScopeDeniedException.class)
                    .satisfies(ex -> assertThat(((LocationScopeDeniedException) ex).permission())
                            .isEqualTo(PeoplePermissions.TIMEKEEPING_APPROVE));
        }

        @Test
        @DisplayName("a location-scoped approver is refused for a person with no assignment (fail closed)")
        void scopedApproverUnassignedPerson() {
            unlinked(MANAGER_USER);
            when(assignmentRepository.findActiveByPersonIdAndDate(ADA, TODAY)).thenReturn(List.of());
            caller(
                    MANAGER_USER,
                    scopedOn(PeoplePermissions.TIMEKEEPING_APPROVE, SHOP_A),
                    PeoplePermissions.TIMEKEEPING_APPROVE);

            assertThatThrownBy(() -> policy().requireMayManage(ADA)).isInstanceOf(LocationScopeDeniedException.class);
        }
    }

    @Nested
    @DisplayName("requireMayView — the single-person clock-state read")
    class View {

        @Test
        @DisplayName("AC7: the person reads their own state with people:self:view alone")
        void selfWithSelfView() {
            linked(ADA_USER, ADA);
            caller(ADA_USER, null, PeoplePermissions.SELF_VIEW);

            assertThatCode(() -> policy().requireMayView(ADA)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the person without people:self:view is refused their own state")
        void selfWithoutSelfView() {
            linked(ADA_USER, ADA);
            caller(ADA_USER, null, "workorder:workorder:view");

            assertThatThrownBy(() -> policy().requireMayView(ADA)).isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("people:self:view does not reach another person's state")
        void selfViewIsNotSupervisory() {
            linked(ADA_USER, ADA);
            caller(ADA_USER, null, PeoplePermissions.SELF_VIEW);

            assertThatThrownBy(() -> policy().requireMayView(GRACE)).isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("a people:timekeeping:view holder reads another person's state within reach")
        void supervisorInReach() {
            unlinked(MANAGER_USER);
            when(assignmentRepository.findActiveByPersonIdAndDate(GRACE, TODAY))
                    .thenReturn(List.of(assignmentAt(SHOP_A)));
            caller(
                    MANAGER_USER,
                    scopedOn(PeoplePermissions.TIMEKEEPING_VIEW, SHOP_A),
                    PeoplePermissions.TIMEKEEPING_VIEW);

            assertThatCode(() -> policy().requireMayView(GRACE)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a people:timekeeping:view holder is refused outside their reach")
        void supervisorOutOfReach() {
            unlinked(MANAGER_USER);
            when(assignmentRepository.findActiveByPersonIdAndDate(GRACE, TODAY))
                    .thenReturn(List.of(assignmentAt(SHOP_B)));
            caller(
                    MANAGER_USER,
                    scopedOn(PeoplePermissions.TIMEKEEPING_VIEW, SHOP_A),
                    PeoplePermissions.TIMEKEEPING_VIEW);

            assertThatThrownBy(() -> policy().requireMayView(GRACE)).isInstanceOf(LocationScopeDeniedException.class);
        }
    }

    @Nested
    @DisplayName("clockStateViewer — per-row visibility on the availability list")
    class Viewer {

        @Test
        @DisplayName("the caller's own row is visible without any timekeeping permission")
        void ownRowIsVisible() {
            linked(ADA_USER, ADA);
            caller(ADA_USER, null, PeoplePermissions.AVAILABILITY_VIEW);

            WorkSessionAccessPolicy policy = policy();
            WorkSessionAccessPolicy.ClockStateViewer viewer = policy.clockStateViewer();

            assertThat(policy.mayViewClockState(viewer, ADA, SHOP_A)).isTrue();
            assertThat(policy.mayViewClockState(viewer, GRACE, SHOP_A)).isFalse();
        }

        @Test
        @DisplayName("OQ1: a people:timekeeping:view holder sees every row within reach, and none outside it")
        void supervisorSeesRowsWithinReach() {
            unlinked(MANAGER_USER);
            caller(
                    MANAGER_USER,
                    scopedOn(PeoplePermissions.TIMEKEEPING_VIEW, SHOP_A),
                    PeoplePermissions.AVAILABILITY_VIEW,
                    PeoplePermissions.TIMEKEEPING_VIEW);

            WorkSessionAccessPolicy policy = policy();
            WorkSessionAccessPolicy.ClockStateViewer viewer = policy.clockStateViewer();

            assertThat(policy.mayViewClockState(viewer, ADA, SHOP_A)).isTrue();
            assertThat(policy.mayViewClockState(viewer, GRACE, SHOP_A)).isTrue();
            assertThat(policy.mayViewClockState(viewer, GRACE, SHOP_B)).isFalse();
        }

        @Test
        @DisplayName("a manager without people:timekeeping:view sees no row but their own")
        void managerWithoutTimekeepingViewSeesOnlySelf() {
            linked(MANAGER_USER, GRACE);
            caller(MANAGER_USER, null, PeoplePermissions.AVAILABILITY_VIEW);

            WorkSessionAccessPolicy policy = policy();
            WorkSessionAccessPolicy.ClockStateViewer viewer = policy.clockStateViewer();

            assertThat(policy.mayViewClockState(viewer, ADA, SHOP_A)).isFalse();
            assertThat(policy.mayViewClockState(viewer, GRACE, SHOP_A)).isTrue();
        }

        @Test
        @DisplayName("the caller's person is resolved once for the whole viewer")
        void resolvesTheCallerOnce() {
            linked(MANAGER_USER, GRACE);
            caller(MANAGER_USER, null, PeoplePermissions.AVAILABILITY_VIEW);

            WorkSessionAccessPolicy policy = policy();
            WorkSessionAccessPolicy.ClockStateViewer viewer = policy.clockStateViewer();
            Arrays.asList(ADA, GRACE, UUID.randomUUID())
                    .forEach(person -> policy.mayViewClockState(viewer, person, SHOP_A));

            verify(userPersonTranslationService, org.mockito.Mockito.times(1)).findPersonUuidForUser(MANAGER_USER);
        }
    }
}
