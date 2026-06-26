package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.entity.PersonContactPoint;
import com.positivity.people.internal.enums.ContactPointType;
import com.positivity.people.internal.enums.EmployeeStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public final class PersonSpecifications {

    private PersonSpecifications() {}

    public static Specification<Person> directoryFilter(String type, String q) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (type != null) {
                // "Is an employee" = has an Employee row (employment lives on the employee
                // table now). The person table also holds customer individuals / commercial
                // contacts (ADR-0015 person unification) which have no Employee row, so they
                // are excluded by the existence subquery.
                switch (type.toUpperCase()) {
                    case "EMPLOYEE" -> predicates.add(root.get("id").in(employeeMatch(query, cb, null)));
                    case "ACTIVE" ->
                        predicates.add(root.get("id").in(employeeMatch(query, cb, List.of(EmployeeStatus.ACTIVE))));
                    case "INACTIVE" ->
                        predicates.add(root.get("id")
                                .in(employeeMatch(
                                        query,
                                        cb,
                                        List.of(
                                                EmployeeStatus.ON_LEAVE,
                                                EmployeeStatus.SUSPENDED,
                                                EmployeeStatus.TERMINATED,
                                                EmployeeStatus.DISABLED))));
                    default -> {
                        /* ALL or unrecognised — no predicate */
                    }
                }
            }

            if (q != null && !q.isBlank()) {
                String pattern = "%" + q.trim().toLowerCase() + "%";
                // Email now lives in person_contact_point; match it via a correlated subquery
                // on EMAIL contact-point values. Username is owned by pos-security (resolved
                // via user_person_link) and is not a Person column, so it is not searchable here.
                Subquery<UUID> emailMatch = query.subquery(UUID.class);
                Root<PersonContactPoint> cp = emailMatch.from(PersonContactPoint.class);
                emailMatch
                        .select(cp.get("personId"))
                        .where(cb.and(
                                cb.equal(cp.get("contactType"), ContactPointType.EMAIL),
                                cb.like(cb.lower(cp.get("value")), pattern)));
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("firstName")), pattern),
                        cb.like(cb.lower(root.get("lastName")), pattern),
                        root.get("id").in(emailMatch)));
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Subquery of person ids that have an Employee row. When {@code statuses} is null the
     * subquery matches any employment record; otherwise it restricts to the given statuses.
     */
    private static Subquery<UUID> employeeMatch(
            CriteriaQuery<?> query, CriteriaBuilder cb, List<EmployeeStatus> statuses) {
        Subquery<UUID> sub = query.subquery(UUID.class);
        Root<Employee> e = sub.from(Employee.class);
        sub.select(e.get("personId"));
        if (statuses != null) {
            sub.where(e.get("status").in(statuses));
        }
        return sub;
    }
}
