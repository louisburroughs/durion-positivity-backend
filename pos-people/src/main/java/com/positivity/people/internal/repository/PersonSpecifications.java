package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.enums.EmployeeStatus;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class PersonSpecifications {

    private PersonSpecifications() {}

    public static Specification<Person> directoryFilter(String type, String q) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (type != null) {
                switch (type.toUpperCase()) {
                    case "EMPLOYEE" -> predicates.add(root.get("status").isNotNull());
                    case "ACTIVE" -> predicates.add(cb.equal(root.get("status"), EmployeeStatus.ACTIVE));
                    case "INACTIVE" -> predicates.add(root.get("status").in(
                            EmployeeStatus.ON_LEAVE,
                            EmployeeStatus.SUSPENDED,
                            EmployeeStatus.TERMINATED,
                            EmployeeStatus.DISABLED));
                    default -> { /* ALL or unrecognised — no predicate */ }
                }
            }

            if (q != null && !q.isBlank()) {
                String pattern = "%" + q.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("firstName")), pattern),
                        cb.like(cb.lower(root.get("lastName")), pattern),
                        cb.like(cb.lower(root.get("primaryEmail")), pattern),
                        cb.like(cb.lower(root.get("username")), pattern)));
            }

            return predicates.isEmpty()
                    ? cb.conjunction()
                    : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
