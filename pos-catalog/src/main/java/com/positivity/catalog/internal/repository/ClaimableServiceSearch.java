package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ServiceEntity;
import com.positivity.catalog.internal.enums.OperationCategory;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The filter behind the capability-picker list (#2246): every service a bay or mobile unit could
 * claim as a {@code serviceCapabilityCode}, narrowed by an optional category and an optional
 * free-text match.
 *
 * <p>"Claimable" means the row exists and carries an operation code. A service has no status
 * column — deletion is how one retires, and {@code CatalogFactPublisher} publishes {@code
 * active=true} on every upsert and a tombstone on delete — so the set of rows with a code is the set
 * pos-location's replica holds as active, which is what its {@code ServiceCapabilityCodeValidator}
 * accepts. A service without a code cannot be claimed at all and is left out.
 *
 * <p>Built as a {@link Specification} rather than a JPQL string of {@code (:param IS NULL OR …)}
 * clauses for the reason {@link FactReplaySearch} gives: an absent filter must emit no SQL, so no
 * untyped placeholder reaches PostgreSQL.
 */
final class ClaimableServiceSearch {

    private static final String OPERATION_CODE = "operationCode";
    private static final String OPERATION_CATEGORY = "operationCategory";
    private static final String NAME = "name";
    private static final char ESCAPE = '\\';

    private ClaimableServiceSearch() {}

    /**
     * The claimable services matching those of the filters that were supplied.
     *
     * @param category only services of this category, or null for every category
     * @param q a case-insensitive substring of the name or the operation code, or null/blank for no
     *     text filter; LIKE wildcards in it are matched literally
     * @return the specification
     */
    @NonNull
    static Specification<ServiceEntity> matching(@Nullable OperationCategory category, @Nullable String q) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(3);
            predicates.add(builder.isNotNull(root.get(OPERATION_CODE)));
            if (category != null) {
                predicates.add(builder.equal(root.get(OPERATION_CATEGORY), category));
            }
            if (q != null && !q.isBlank()) {
                String pattern = "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get(NAME)), pattern, ESCAPE),
                        builder.like(builder.lower(root.get(OPERATION_CODE)), pattern, ESCAPE)));
            }
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static String escapeLike(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c == ESCAPE || c == '%' || c == '_') {
                escaped.append(ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
