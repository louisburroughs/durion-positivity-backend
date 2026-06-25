package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.entity.PersonContactPoint;
import com.positivity.people.internal.enums.ContactPointType;
import com.positivity.people.internal.enums.EmployeeStatus;
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
                // employee_number is the discriminator for "is an employee". The person
                // table also holds customer individuals and commercial contacts (ADR-0015
                // person unification), so status alone is not sufficient — those records
                // may carry an EmployeeStatus without being employees.
                switch (type.toUpperCase()) {
                    case "EMPLOYEE" -> predicates.add(cb.isNotNull(root.get("employeeNumber")));
                    case "ACTIVE" -> {
                        predicates.add(cb.isNotNull(root.get("employeeNumber")));
                        predicates.add(cb.equal(root.get("status"), EmployeeStatus.ACTIVE));
                    }
                    case "INACTIVE" -> {
                        predicates.add(cb.isNotNull(root.get("employeeNumber")));
                        predicates.add(root.get("status")
                                .in(
                                        EmployeeStatus.ON_LEAVE,
                                        EmployeeStatus.SUSPENDED,
                                        EmployeeStatus.TERMINATED,
                                        EmployeeStatus.DISABLED));
                    }
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
}
