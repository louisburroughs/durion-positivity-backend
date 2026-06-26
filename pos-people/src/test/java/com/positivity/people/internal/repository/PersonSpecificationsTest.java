package com.positivity.people.internal.repository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.Person;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies the people directory employee filters now discriminate via a correlated
 * subquery over the {@link Employee} entity (employment moved off the person table),
 * keyed by {@code person_id} and matched against {@code person.id}. The EMPLOYEE filter
 * matches existence of any employment row; ACTIVE/INACTIVE additionally constrain the
 * subquery on employee status. The person table also holds customer individuals /
 * commercial contacts (ADR-0015 person unification) that have no employee row.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class PersonSpecificationsTest {

    private Root<Person> mockRoot() {
        Root<Person> root = mock(Root.class);
        Path path = mock(Path.class);
        when(root.get(any(String.class))).thenReturn(path);
        // root.get("id").in(subquery) must yield a Predicate to be collected.
        when(path.in(any(Subquery.class))).thenReturn(mock(Predicate.class));
        return root;
    }

    private CriteriaBuilder mockCb() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        when(cb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));
        when(cb.conjunction()).thenReturn(mock(Predicate.class));
        return cb;
    }

    /**
     * Stubs {@code query.subquery(...)} to return a mocked {@link Subquery} whose
     * {@code from}/{@code select} fluent calls return safe mocks, and returns that
     * same subquery so individual tests can verify interactions against it.
     */
    private Subquery<UUID> mockSubquery(CriteriaQuery<?> query) {
        Subquery<UUID> sub = mock(Subquery.class);
        Root<Employee> employeeRoot = mock(Root.class);
        Path employeePath = mock(Path.class);
        when(employeeRoot.get(any(String.class))).thenReturn(employeePath);
        // e.get("status").in(statuses) feeds sub.where(...); keep it non-null.
        when(employeePath.in(any(java.util.Collection.class))).thenReturn(mock(Predicate.class));
        when(sub.from(any(Class.class))).thenReturn((Root) employeeRoot);
        when(sub.select(any())).thenReturn((Subquery) sub);
        when(query.subquery(any(Class.class))).thenReturn((Subquery) sub);
        return sub;
    }

    @Test
    void employeeFilter_matchesPersonIdsWithAnEmployeeRow_noStatusConstraint() {
        Root<Person> root = mockRoot();
        CriteriaBuilder cb = mockCb();
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        Subquery<UUID> sub = mockSubquery(query);

        PersonSpecifications.directoryFilter("EMPLOYEE", null).toPredicate(root, query, cb);

        // A correlated subquery over the Employee entity is built and matched on person.id.
        verify(query).subquery(UUID.class);
        verify(sub).from(Employee.class);
        verify(sub).select(any());
        verify(root).get("id");
        // EMPLOYEE = existence only; no status restriction is applied to the subquery.
        verify(sub, never()).where(any(Predicate.class));
    }

    @Test
    void activeFilter_constrainsEmployeeSubqueryOnStatus() {
        Root<Person> root = mockRoot();
        CriteriaBuilder cb = mockCb();
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        Subquery<UUID> sub = mockSubquery(query);

        PersonSpecifications.directoryFilter("ACTIVE", null).toPredicate(root, query, cb);

        verify(query).subquery(UUID.class);
        verify(sub).from(Employee.class);
        verify(root).get("id");
        // ACTIVE additionally restricts the subquery by employee status.
        verify(sub).where(any(Predicate.class));
    }

    @Test
    void inactiveFilter_constrainsEmployeeSubqueryOnStatus() {
        Root<Person> root = mockRoot();
        CriteriaBuilder cb = mockCb();
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        Subquery<UUID> sub = mockSubquery(query);

        PersonSpecifications.directoryFilter("INACTIVE", null).toPredicate(root, query, cb);

        verify(query).subquery(UUID.class);
        verify(sub).from(Employee.class);
        verify(root).get("id");
        // INACTIVE restricts the subquery to the non-active statuses.
        verify(sub).where(any(Predicate.class));
    }

    @Test
    void allFilter_addsNoTypePredicateOrSubquery() {
        Root<Person> root = mockRoot();
        CriteriaBuilder cb = mockCb();
        CriteriaQuery<?> query = mock(CriteriaQuery.class);

        PersonSpecifications.directoryFilter("ALL", null).toPredicate(root, query, cb);

        // ALL must not build an employee subquery nor touch the root for filtering.
        verify(query, never()).subquery(any(Class.class));
        verify(root, never()).get(any(String.class));
    }
}
