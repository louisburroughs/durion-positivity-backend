package com.positivity.people.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.people.PostgresSliceTestBase;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.entity.EmployeeOffboardingRetry;
import com.positivity.people.internal.enums.AssignmentStatus;
import com.positivity.people.internal.enums.AssignmentTerminationPolicy;
import com.positivity.people.internal.enums.EmployeeStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The staffing-assignment searches ({@link EmployeeLocationAssignmentRepository}) against the real
 * PostgreSQL schema.
 *
 * <h2>What this defends</h2>
 *
 * Two of these queries carry an optional {@code effectiveTo} written as {@code (:effectiveTo IS NULL
 * OR …)}, and a {@link LocalDate} placeholder is the one case where PostgreSQL's parse-time type
 * inference is <em>asymmetric</em>: pgjdbc's {@code setNull(Types.DATE)} carries a concrete {@code
 * date} type OID, but a bound date is sent with an unspecified OID so the server can coerce it. So
 * the overlap checks parsed while the caller left the end date open and failed with {@code could not
 * determine data type of parameter $n} the moment one was supplied — creating or updating a
 * closed-ended assignment was a 500 while the identical open-ended call succeeded (issue #1891, the
 * same asymmetry PR #1962 found on pos-catalog's MSRP overlap query). Each overlap check is
 * therefore exercised both ways, in its own transaction, and the supplied-date case is the one that
 * used to fail.
 *
 * <p>The optional {@code locationId} of the availability search is a {@code UUID} and infers fine in
 * both directions; it is pinned here, not rewritten, so that adding a temporal filter to that query
 * later fails this test rather than production.
 */
@DisplayName("Staffing assignment searches on PostgreSQL (#1891)")
class EmployeeLocationAssignmentRepositoryTest extends PostgresSliceTestBase {

    private static final LocalDate WINDOW_START = LocalDate.of(2026, 3, 1);
    private static final LocalDate WINDOW_END = LocalDate.of(2026, 3, 31);
    private static final LocalDate INSIDE_WINDOW = LocalDate.of(2026, 3, 15);
    private static final String ROLE = "TECHNICIAN";

    @Autowired
    private EmployeeLocationAssignmentRepository assignments;

    @Autowired
    private EmployeeRepository employees;

    @Autowired
    private EmployeeOffboardingRetryRepository retries;

    private Employee employee() {
        return employees.saveAndFlush(
                Employee.builder().personId(UUID.randomUUID()).build());
    }

    private EmployeeLocationAssignment assignment(
            Employee employee, UUID locationId, LocalDate effectiveFrom, LocalDate effectiveTo) {
        return assignments.saveAndFlush(EmployeeLocationAssignment.builder()
                .employee(employee)
                .locationId(locationId)
                .role(ROLE)
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .status(AssignmentStatus.ACTIVE)
                .build());
    }

    @Nested
    @DisplayName("existsOverlapping — the gate on creating an assignment")
    class ExistsOverlapping {

        @Test
        @DisplayName("an open-ended candidate is checked without failing to parse")
        void openEndedCandidateIsChecked() {
            Employee employee = employee();
            UUID location = UUID.randomUUID();
            assignment(employee, location, WINDOW_START, WINDOW_END);

            assertThat(assignments.existsOverlapping(employee.getPersonId(), location, ROLE, INSIDE_WINDOW, null))
                    .isTrue();
            assertThat(assignments.existsOverlapping(
                            employee.getPersonId(), location, ROLE, WINDOW_END.plusDays(1), null))
                    .isFalse();
        }

        @Test
        @DisplayName("a closed-ended candidate is checked too — the case that used to be a 500")
        void closedEndedCandidateIsChecked() {
            Employee employee = employee();
            UUID location = UUID.randomUUID();
            assignment(employee, location, WINDOW_START, WINDOW_END);

            assertThat(assignments.existsOverlapping(employee.getPersonId(), location, ROLE, WINDOW_START, WINDOW_END))
                    .as("a candidate over the same window overlaps")
                    .isTrue();
            assertThat(assignments.existsOverlapping(
                            employee.getPersonId(), location, ROLE, WINDOW_END.plusDays(1), WINDOW_END.plusDays(10)))
                    .as("a candidate entirely after the existing window does not")
                    .isFalse();
            assertThat(assignments.existsOverlapping(
                            employee.getPersonId(),
                            location,
                            ROLE,
                            WINDOW_START.minusDays(10),
                            WINDOW_START.minusDays(1)))
                    .as("a candidate entirely before it does not either")
                    .isFalse();
        }

        @Test
        @DisplayName("an existing open-ended assignment overlaps everything after it starts")
        void openEndedExistingAssignmentOverlapsLater() {
            Employee employee = employee();
            UUID location = UUID.randomUUID();
            assignment(employee, location, WINDOW_START, null);

            assertThat(assignments.existsOverlapping(
                            employee.getPersonId(), location, ROLE, WINDOW_END.plusYears(1), WINDOW_END.plusYears(2)))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("existsOverlappingExcludingId — the gate on updating one")
    class ExistsOverlappingExcludingId {

        @Test
        @DisplayName("an open-ended candidate is checked without failing to parse")
        void openEndedCandidateIsChecked() {
            Employee employee = employee();
            UUID location = UUID.randomUUID();
            EmployeeLocationAssignment existing = assignment(employee, location, WINDOW_START, WINDOW_END);

            assertThat(assignments.existsOverlappingExcludingId(
                            existing.getId(), employee.getPersonId(), location, ROLE, INSIDE_WINDOW, null))
                    .as("an assignment cannot overlap itself")
                    .isFalse();
        }

        @Test
        @DisplayName("a closed-ended candidate is checked too — the case that used to be a 500")
        void closedEndedCandidateIsChecked() {
            Employee employee = employee();
            UUID location = UUID.randomUUID();
            EmployeeLocationAssignment existing = assignment(employee, location, WINDOW_START, WINDOW_END);
            assignment(employee, location, WINDOW_END.plusDays(1), WINDOW_END.plusDays(30));

            assertThat(assignments.existsOverlappingExcludingId(
                            existing.getId(), employee.getPersonId(), location, ROLE, WINDOW_START, WINDOW_END))
                    .as("the excluded row is ignored and nothing else covers this window")
                    .isFalse();
            assertThat(assignments.existsOverlappingExcludingId(
                            existing.getId(),
                            employee.getPersonId(),
                            location,
                            ROLE,
                            WINDOW_END.plusDays(2),
                            WINDOW_END.plusDays(3)))
                    .as("the sibling assignment does overlap")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("findActiveByDateAndOptionalLocation — the availability roster")
    class FindActiveByDateAndOptionalLocation {

        @Test
        @DisplayName("an absent location filter returns every active assignment on the date")
        void absentLocationReturnsEveryAssignment() {
            Employee employee = employee();
            UUID location = UUID.randomUUID();
            EmployeeLocationAssignment active = assignment(employee, location, WINDOW_START, WINDOW_END);

            assertThat(assignments.findActiveByDateAndOptionalLocation(INSIDE_WINDOW, null))
                    .contains(active);
        }

        @Test
        @DisplayName("a supplied location filter narrows to that location")
        void suppliedLocationNarrows() {
            Employee employee = employee();
            UUID wanted = UUID.randomUUID();
            UUID other = UUID.randomUUID();
            EmployeeLocationAssignment atWanted = assignment(employee, wanted, WINDOW_START, WINDOW_END);
            EmployeeLocationAssignment atOther = assignment(employee(), other, WINDOW_START, WINDOW_END);

            assertThat(assignments.findActiveByDateAndOptionalLocation(INSIDE_WINDOW, wanted))
                    .contains(atWanted)
                    .doesNotContain(atOther);
        }

        @Test
        @DisplayName("an open-ended assignment is active on every date after it starts")
        void openEndedAssignmentStaysActive() {
            Employee employee = employee();
            UUID location = UUID.randomUUID();
            EmployeeLocationAssignment openEnded = assignment(employee, location, WINDOW_START, null);

            assertThat(assignments.findActiveByDateAndOptionalLocation(WINDOW_END.plusYears(5), location))
                    .containsExactly(openEnded);
        }

        @Test
        @DisplayName("a date outside the window selects nothing")
        void dateOutsideTheWindowSelectsNothing() {
            Employee employee = employee();
            UUID location = UUID.randomUUID();
            assignment(employee, location, WINDOW_START, WINDOW_END);

            assertThat(assignments.findActiveByDateAndOptionalLocation(WINDOW_END.plusDays(1), location))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("findActiveByPersonIdAndDate — one person's active assignments")
    class FindActiveByPersonIdAndDate {

        @Test
        @DisplayName("selects the person's assignments covering the date, primary first")
        void selectsAssignmentsCoveringTheDate() {
            Employee employee = employee();
            EmployeeLocationAssignment covering = assignment(employee, UUID.randomUUID(), WINDOW_START, WINDOW_END);
            assignment(employee, UUID.randomUUID(), WINDOW_END.plusDays(1), WINDOW_END.plusDays(30));

            assertThat(assignments.findActiveByPersonIdAndDate(employee.getPersonId(), INSIDE_WINDOW))
                    .containsExactly(covering);
        }
    }

    @Nested
    @DisplayName("findOpenForOffboardedEmployees — the offboarding sweep (#2121)")
    class OpenForOffboardedEmployees {

        private final Instant now = Instant.parse("2026-04-01T12:00:00Z");
        private final Instant settledBefore = now.minusSeconds(300);
        private final LocalDate today = LocalDate.of(2026, 4, 1);

        private Employee employeeWithStatus(EmployeeStatus status, Instant statusEffectiveAt) {
            return employees.saveAndFlush(Employee.builder()
                    .personId(UUID.randomUUID())
                    .status(status)
                    .statusEffectiveAt(statusEffectiveAt)
                    .build());
        }

        @Test
        @DisplayName("returns expired and settled open-ended ACTIVE assignments of offboarded employees only")
        void returnsOnlyWhatAnOffboardingShouldHaveEnded() {
            Instant longAgo = now.minusSeconds(3600);
            EmployeeLocationAssignment expired = assignment(
                    employeeWithStatus(EmployeeStatus.DISABLED, longAgo),
                    UUID.randomUUID(),
                    WINDOW_START,
                    today.minusDays(1));
            EmployeeLocationAssignment openSettled = assignment(
                    employeeWithStatus(EmployeeStatus.TERMINATED, longAgo), UUID.randomUUID(), WINDOW_START, null);
            // Excluded: still inside its grace period, open-ended but just disabled, employee still working.
            assignment(employeeWithStatus(EmployeeStatus.DISABLED, longAgo), UUID.randomUUID(), WINDOW_START, today);
            assignment(employeeWithStatus(EmployeeStatus.DISABLED, now), UUID.randomUUID(), WINDOW_START, null);
            assignment(employeeWithStatus(EmployeeStatus.ACTIVE, longAgo), UUID.randomUUID(), WINDOW_START, null);
            assignment(
                    employeeWithStatus(EmployeeStatus.ACTIVE, longAgo),
                    UUID.randomUUID(),
                    WINDOW_START,
                    today.minusDays(1));
            EmployeeLocationAssignment alreadyEnded = assignment(
                    employeeWithStatus(EmployeeStatus.DISABLED, longAgo),
                    UUID.randomUUID(),
                    WINDOW_START,
                    today.minusDays(1));
            alreadyEnded.setStatus(AssignmentStatus.ENDED);
            assignments.saveAndFlush(alreadyEnded);

            assertThat(assignments.findOpenForOffboardedEmployees(
                            today, settledBefore, List.of(EmployeeStatus.DISABLED, EmployeeStatus.TERMINATED), 10))
                    .extracting(EmployeeLocationAssignment::getId)
                    .containsExactlyInAnyOrder(expired.getId(), openSettled.getId());
        }

        @Test
        @DisplayName("a pending retry row keeps an open-ended assignment out; an exhausted one does not")
        void onlyAPendingRetryRowHoldsAnOpenEndedAssignmentBack() {
            Instant longAgo = now.minusSeconds(3600);
            Employee queued = employeeWithStatus(EmployeeStatus.DISABLED, longAgo);
            assignment(queued, UUID.randomUUID(), WINDOW_START, null);
            retryRow(queued, 3);
            Employee givenUp = employeeWithStatus(EmployeeStatus.DISABLED, longAgo);
            EmployeeLocationAssignment abandoned = assignment(givenUp, UUID.randomUUID(), WINDOW_START, null);
            retryRow(givenUp, 10);

            assertThat(assignments.findOpenForOffboardedEmployees(
                            today, settledBefore, List.of(EmployeeStatus.DISABLED, EmployeeStatus.TERMINATED), 10))
                    .extracting(EmployeeLocationAssignment::getId)
                    .containsExactly(abandoned.getId());
        }

        private void retryRow(Employee employee, int attempts) {
            EmployeeOffboardingRetry retry = new EmployeeOffboardingRetry();
            retry.setEmployeeId(employee.getPersonId());
            retry.setAssignmentPolicy(AssignmentTerminationPolicy.IMMEDIATE);
            retry.setActorId("system");
            retry.setFailureReason("boom");
            retry.setAttempts(attempts);
            retry.setNextAttemptAt(now.plusSeconds(300));
            retries.saveAndFlush(retry);
        }
    }
}
