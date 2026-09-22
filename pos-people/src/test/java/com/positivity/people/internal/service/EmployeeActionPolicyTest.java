package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.people.internal.enums.AllowedAction;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.security.PeoplePermissions;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The authoritative DISABLE/ENABLE matrix (durion#2159), plus the permission-only VIEW_PII and
 * UPDATE rules, exercised directly against {@link EmployeeActionPolicy#allowedActions}. {@code
 * EmployeeServiceImplTest.ActionPolicyAgreesWithServiceGuards} is the complementary check that
 * this matrix does not drift from {@code disableEmployee}/{@code enableEmployee}'s real guards.
 */
@DisplayName("EmployeeActionPolicy")
class EmployeeActionPolicyTest {

    private static final Set<String> HOLDS_ACTIVATION = Set.of(PeoplePermissions.EMPLOYEE_ACTIVATION);
    private static final Set<String> LACKS_ACTIVATION = Set.of();

    private final EmployeeActionPolicy policy = new EmployeeActionPolicy();

    /**
     * The matrix from this issue, verbatim: for every {@link EmployeeStatus}, whether DISABLE and
     * ENABLE are offered to a caller holding {@code people:employee:activation} and to one that
     * does not. Ten rows -- the five statuses this lifecycle has today, each with and without the
     * permission -- which is the minimum the issue calls for.
     */
    private static Stream<Arguments> matrixCases() {
        return Stream.of(
                // status, holdsActivation, expectDisable, expectEnable
                Arguments.of(EmployeeStatus.ACTIVE, true, true, false),
                Arguments.of(EmployeeStatus.ACTIVE, false, false, false),
                Arguments.of(EmployeeStatus.DISABLED, true, false, true),
                Arguments.of(EmployeeStatus.DISABLED, false, false, false),
                Arguments.of(EmployeeStatus.ON_LEAVE, true, false, false),
                Arguments.of(EmployeeStatus.ON_LEAVE, false, false, false),
                Arguments.of(EmployeeStatus.SUSPENDED, true, false, false),
                Arguments.of(EmployeeStatus.SUSPENDED, false, false, false),
                Arguments.of(EmployeeStatus.TERMINATED, true, false, false),
                Arguments.of(EmployeeStatus.TERMINATED, false, false, false));
    }

    @ParameterizedTest(name = "{0}, holdsActivation={1} -> DISABLE={2}, ENABLE={3}")
    @MethodSource("matrixCases")
    void matchesTheAuthoritativeDisableEnableMatrix(
            EmployeeStatus status, boolean holdsActivation, boolean expectDisable, boolean expectEnable) {
        List<AllowedAction> actions =
                policy.allowedActions(status, holdsActivation ? HOLDS_ACTIVATION : LACKS_ACTIVATION);

        assertThat(actions.contains(AllowedAction.DISABLE)).isEqualTo(expectDisable);
        assertThat(actions.contains(AllowedAction.ENABLE)).isEqualTo(expectEnable);
    }

    @Test
    @DisplayName("VIEW_PII follows people:employee_pii:view alone, independent of status")
    void viewPiiFollowsThePiiPermissionForEveryStatus() {
        for (EmployeeStatus status : EmployeeStatus.values()) {
            assertThat(policy.allowedActions(status, Set.of(PeoplePermissions.EMPLOYEE_PII_VIEW))
                            .contains(AllowedAction.VIEW_PII))
                    .as("status %s, holding EMPLOYEE_PII_VIEW", status)
                    .isTrue();
            assertThat(policy.allowedActions(status, Set.of()).contains(AllowedAction.VIEW_PII))
                    .as("status %s, holding nothing", status)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("UPDATE follows people:employee:edit alone, independent of status (including TERMINATED)")
    void updateFollowsTheEditPermissionForEveryStatus() {
        // EmployeeServiceImpl#updateEmployee enforces no status guard today -- see
        // EmployeeActionPolicy's javadoc for the DECISION-PEOPLE-025 gap this tracks
        // intentionally, TERMINATED included, rather than inventing an unenforced restriction.
        for (EmployeeStatus status : EmployeeStatus.values()) {
            assertThat(policy.allowedActions(status, Set.of(PeoplePermissions.EMPLOYEE_EDIT))
                            .contains(AllowedAction.UPDATE))
                    .as("status %s, holding EMPLOYEE_EDIT", status)
                    .isTrue();
            assertThat(policy.allowedActions(status, Set.of()).contains(AllowedAction.UPDATE))
                    .as("status %s, holding nothing", status)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("A null status offers no status-gated action, permission held or not")
    void nullStatusOffersNeitherDisableNorEnable() {
        assertThat(policy.allowedActions(null, HOLDS_ACTIVATION))
                .doesNotContain(AllowedAction.DISABLE, AllowedAction.ENABLE);
    }

    @Test
    @DisplayName("A caller holding every relevant permission gets every applicable action, never more")
    void aFullyPermissionedCallerGetsExactlyTheApplicableActions() {
        Set<String> everything = Set.of(
                PeoplePermissions.EMPLOYEE_PII_VIEW,
                PeoplePermissions.EMPLOYEE_EDIT,
                PeoplePermissions.EMPLOYEE_ACTIVATION);

        assertThat(policy.allowedActions(EmployeeStatus.ACTIVE, everything))
                .containsExactlyInAnyOrder(AllowedAction.VIEW_PII, AllowedAction.UPDATE, AllowedAction.DISABLE);
        assertThat(policy.allowedActions(EmployeeStatus.TERMINATED, everything))
                .containsExactlyInAnyOrder(AllowedAction.VIEW_PII, AllowedAction.UPDATE);
    }

    @Test
    @DisplayName("A caller holding nothing gets no actions, for every status")
    void aCallerHoldingNothingGetsNoActions() {
        for (EmployeeStatus status : EmployeeStatus.values()) {
            assertThat(policy.allowedActions(status, Set.of())).isEmpty();
        }
    }

    @Test
    @DisplayName("currentCallerAuthorities degrades to empty rather than throwing with no security context")
    void currentCallerAuthoritiesDegradesGracefullyWithNoSecurityContext() {
        // No TestingAuthenticationToken is installed in this test class on purpose: this is
        // exactly the "no authenticated caller" case allowedActionsForCurrentCaller's javadoc
        // documents, and it must return an empty rendering hint rather than propagate
        // SecurityContextHelper's fail-fast IllegalStateException up through a DTO builder.
        assertThat(policy.currentCallerAuthorities()).isEmpty();
        assertThat(policy.allowedActionsForCurrentCaller(EmployeeStatus.ACTIVE)).isEmpty();
    }
}
