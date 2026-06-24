package com.positivity.people.internal.repository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.entity.Person;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;

/**
 * Verifies the people directory employee filters discriminate on employee_number,
 * not status. After ADR-0015 person unification the person table also holds
 * customer individuals / commercial contacts that may carry an EmployeeStatus.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class PersonSpecificationsTest {

    private Root<Person> mockRoot() {
        Root<Person> root = mock(Root.class);
        when(root.get(any(String.class))).thenReturn(mock(Path.class));
        return root;
    }

    private CriteriaBuilder mockCb() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        when(cb.isNotNull(any())).thenReturn(mock(Predicate.class));
        when(cb.equal(any(), any())).thenReturn(mock(Predicate.class));
        when(cb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));
        when(cb.conjunction()).thenReturn(mock(Predicate.class));
        return cb;
    }

    @Test
    void employeeFilter_gatesOnEmployeeNumber_notStatus() {
        Root<Person> root = mockRoot();
        CriteriaBuilder cb = mockCb();

        PersonSpecifications.directoryFilter("EMPLOYEE", null).toPredicate(root, mock(CriteriaQuery.class), cb);

        verify(root).get("employeeNumber");
        verify(cb).isNotNull(any());
        verify(root, never()).get("status");
    }

    @Test
    void activeFilter_requiresEmployeeNumberAndActiveStatus() {
        Root<Person> root = mockRoot();
        CriteriaBuilder cb = mockCb();

        PersonSpecifications.directoryFilter("ACTIVE", null).toPredicate(root, mock(CriteriaQuery.class), cb);

        verify(root).get("employeeNumber");
        verify(root).get("status");
    }

    @Test
    void allFilter_addsNoTypePredicate() {
        Root<Person> root = mockRoot();
        CriteriaBuilder cb = mockCb();

        PersonSpecifications.directoryFilter("ALL", null).toPredicate(root, mock(CriteriaQuery.class), cb);

        verify(root, never()).get("employeeNumber");
        verify(root, never()).get("status");
    }
}
