package com.positivity.peoplecontact.internal.repository;

import com.positivity.peoplecontact.internal.entity.Person;
import com.positivity.peoplecontact.internal.entity.PersonContactPoint;
import com.positivity.peoplecontact.internal.enums.ContactPointType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public final class PersonSpecifications {

    private PersonSpecifications() {}

    /**
     * Directory search over identity data only. Employment lives in pos-people (ADR-0044 §6),
     * so employment-status filtering is not available here — HR-facing directory views query
     * pos-people instead.
     */
    public static Specification<Person> directoryFilter(String q) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (q != null && !q.isBlank()) {
                String pattern = "%" + q.trim().toLowerCase() + "%";
                // Email lives in person_contact_point; match it via a correlated subquery
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
