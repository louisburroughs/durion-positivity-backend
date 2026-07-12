package com.positivity.peoplecontact.internal.repository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.peoplecontact.internal.entity.Person;
import com.positivity.peoplecontact.internal.entity.PersonContactPoint;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies the people-contact directory search matches name columns and EMAIL contact-point
 * values via a correlated subquery over {@link PersonContactPoint}. Employment filters moved to
 * pos-people with the ADR-0044 §6 split — identity search here is name/email only.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class PersonSpecificationsTest {

    private Root<Person> mockRoot() {
        Root<Person> root = mock(Root.class);
        Path path = mock(Path.class);
        when(root.get(any(String.class))).thenReturn(path);
        when(path.in(any(Subquery.class))).thenReturn(mock(Predicate.class));
        return root;
    }

    private CriteriaBuilder mockCb() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        when(cb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));
        when(cb.and(any(Expression.class), any(Expression.class))).thenReturn(mock(Predicate.class));
        when(cb.or(any(Predicate[].class))).thenReturn(mock(Predicate.class));
        when(cb.conjunction()).thenReturn(mock(Predicate.class));
        when(cb.lower(any())).thenReturn(mock(Expression.class));
        when(cb.like(any(Expression.class), any(String.class))).thenReturn(mock(Predicate.class));
        when(cb.equal(any(Expression.class), any(Object.class))).thenReturn(mock(Predicate.class));
        return cb;
    }

    private Subquery<UUID> mockSubquery(CriteriaQuery<?> query) {
        Subquery<UUID> sub = mock(Subquery.class);
        Root<PersonContactPoint> cpRoot = mock(Root.class);
        Path cpPath = mock(Path.class);
        when(cpRoot.get(any(String.class))).thenReturn(cpPath);
        when(sub.from(any(Class.class))).thenReturn((Root) cpRoot);
        when(sub.select(any())).thenReturn((Subquery) sub);
        when(query.subquery(any(Class.class))).thenReturn((Subquery) sub);
        return sub;
    }

    @Test
    void queryFilter_matchesNamesAndEmailContactPoints() {
        Root<Person> root = mockRoot();
        CriteriaBuilder cb = mockCb();
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        Subquery<UUID> sub = mockSubquery(query);

        PersonSpecifications.directoryFilter("smith").toPredicate(root, query, cb);

        // A correlated subquery over PersonContactPoint is built for the email match…
        verify(query).subquery(UUID.class);
        verify(sub).from(PersonContactPoint.class);
        verify(sub).select(any());
        verify(sub).where(any(Predicate.class));
        // …and the name columns are matched on the person root.
        verify(root).get("firstName");
        verify(root).get("lastName");
    }

    @Test
    void blankQuery_addsNoPredicateOrSubquery() {
        Root<Person> root = mockRoot();
        CriteriaBuilder cb = mockCb();
        CriteriaQuery<?> query = mock(CriteriaQuery.class);

        PersonSpecifications.directoryFilter("  ").toPredicate(root, query, cb);

        verify(query, never()).subquery(any(Class.class));
        verify(root, never()).get(any(String.class));
    }

    @Test
    void nullQuery_addsNoPredicateOrSubquery() {
        Root<Person> root = mockRoot();
        CriteriaBuilder cb = mockCb();
        CriteriaQuery<?> query = mock(CriteriaQuery.class);

        PersonSpecifications.directoryFilter(null).toPredicate(root, query, cb);

        verify(query, never()).subquery(any(Class.class));
        verify(root, never()).get(any(String.class));
    }
}
