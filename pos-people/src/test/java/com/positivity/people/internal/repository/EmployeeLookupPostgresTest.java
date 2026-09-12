package com.positivity.people.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.people.PostgresSliceTestBase;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.ExtPersonReplica;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The module's case-folded lookups against the real PostgreSQL schema.
 *
 * <h2>What this defends</h2>
 *
 * A derived {@code IgnoreCase} finder puts its parameter inside {@code upper(…)} rather than beside
 * the bare column, which is the shape that made a {@code String} parameter fail in pos-tax with
 * {@code function upper(bytea) does not exist}: with nothing to infer a type from, Hibernate binds
 * the placeholder as opaque binary and PostgreSQL is asked for a function that takes one (issue
 * #1891). These finders do not fail — Spring Data derives the parameter's type from the property it
 * was derived against, so the placeholder is bound as text — but that is a property of the statement
 * PostgreSQL receives, and only PostgreSQL can confirm it. A hand-written replacement for one of
 * these that folded case on a bare parameter would fail, and would fail on H2 nowhere.
 */
@DisplayName("Case-folded lookups on PostgreSQL (#1891)")
class EmployeeLookupPostgresTest extends PostgresSliceTestBase {

    private static final String EMPLOYEE_NUMBER = "EMP-Casing-42";

    @Autowired
    private EmployeeRepository employees;

    @Autowired
    private ExtPersonReplicaRepository people;

    @Test
    @DisplayName("an employee number is found whatever case the caller types it in")
    void employeeNumberIsFoundIgnoringCase() {
        UUID personId = UUID.randomUUID();
        employees.saveAndFlush(Employee.builder()
                .personId(personId)
                .employeeNumber(EMPLOYEE_NUMBER)
                .build());

        assertThat(employees.findByEmployeeNumberIgnoreCase(EMPLOYEE_NUMBER.toLowerCase(Locale.ROOT)))
                .get()
                .extracting(Employee::getPersonId)
                .isEqualTo(personId);
        assertThat(employees.existsByEmployeeNumberIgnoreCase(EMPLOYEE_NUMBER.toUpperCase(Locale.ROOT)))
                .isTrue();
        assertThat(employees.existsByEmployeeNumberIgnoreCaseAndPersonIdNot(EMPLOYEE_NUMBER, personId))
                .as("the holder of the number does not collide with itself")
                .isFalse();
        assertThat(employees.existsByEmployeeNumberIgnoreCase("EMP-no-such-number"))
                .as("a number nobody holds is absent, rather than a failed statement")
                .isFalse();
    }

    @Test
    @DisplayName("a replicated person is found by name and email whatever case the caller types")
    void replicaIsFoundIgnoringCase() {
        ExtPersonReplica replica = new ExtPersonReplica();
        UUID personId = UUID.randomUUID();
        replica.setPersonId(personId);
        replica.setFirstName("Dana");
        replica.setLastName("O'Hare");
        replica.setPrimaryEmail("Dana.OHare@example.test");
        people.saveAndFlush(replica);

        // Compared by id: auditing stamps updatedAt on save and the entity carries value equality,
        // so a detached fixture never equals the row that comes back.
        assertThat(people.findByLastNameIgnoreCase("o'hare"))
                .extracting(ExtPersonReplica::getPersonId)
                .contains(personId);
        assertThat(people.findByPrimaryEmailIgnoreCase("dana.ohare@example.test"))
                .extracting(ExtPersonReplica::getPersonId)
                .contains(personId);
        assertThat(people.findByLastNameIgnoreCase("nobody")).isEmpty();
    }
}
