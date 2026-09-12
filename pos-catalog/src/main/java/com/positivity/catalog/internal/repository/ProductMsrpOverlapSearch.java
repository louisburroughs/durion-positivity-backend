package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ProductMsrpEntity;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The overlap check an MSRP record is admitted by, built as a {@link Specification} so that an
 * absent filter emits no SQL at all — see {@link FactReplaySearch} for why the {@code (:param IS
 * NULL OR …)} JPQL this replaces made PostgreSQL reject the statement at parse time with {@code
 * could not determine data type of parameter $n} (the defect of issue #1891).
 *
 * <h2>This one failed only half the time, which is worse</h2>
 *
 * The untyped placeholder was {@code $4}, the {@code IS NULL} test on {@code endDate}. Unlike the
 * {@link java.time.Instant} filters elsewhere in this module, a {@link LocalDate} <em>is</em> typed
 * by the pgjdbc driver when it binds a null — {@code setNull(Types.DATE)} carries the {@code date}
 * type OID — but not when it binds a value, because the driver sends dates and timestamps with an
 * unspecified OID so the server can coerce them. So the query worked for an open-ended MSRP and was
 * a 500 for a closed one: creating or updating a record with an {@code effectiveEndDate} failed
 * every time, and the identical call without one succeeded. A defect that tracks the shape of the
 * request rather than the endpoint is exactly the kind a green H2 test suite hides longest.
 *
 * <h2>An open-ended record overlaps everything after it starts</h2>
 *
 * A record with no {@code effectiveEndDate} runs forever, so the lower-bound clause admits a null
 * end date alongside the comparison. Both bounds are inclusive: two records that merely touch at a
 * day do overlap on that day.
 */
final class ProductMsrpOverlapSearch {

    private static final String PRODUCT = "product";
    private static final String ID = "id";
    private static final String MSRP_ID = "msrpId";
    private static final String EFFECTIVE_START_DATE = "effectiveStartDate";
    private static final String EFFECTIVE_END_DATE = "effectiveEndDate";

    private ProductMsrpOverlapSearch() {}

    /**
     * The MSRP records of one product whose effective window overlaps the candidate's.
     *
     * @param productId the product whose MSRP history is being checked
     * @param startDate the candidate's inclusive start; required, as an MSRP record always has one
     * @param endDate the candidate's inclusive end, or null for an open-ended candidate — which has
     *     no upper bound, so the upper-bound predicate is switched off rather than matching nothing
     * @param excludeMsrpId the record being updated, excluded so it cannot overlap itself, or null
     *     when the candidate is new
     * @return the specification matching the overlapping records
     */
    @NonNull
    static Specification<ProductMsrpEntity> matching(
            @NonNull UUID productId,
            @NonNull LocalDate startDate,
            @Nullable LocalDate endDate,
            @Nullable UUID excludeMsrpId) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(4);
            predicates.add(builder.equal(root.get(PRODUCT).get(ID), productId));
            predicates.add(builder.or(
                    builder.isNull(root.get(EFFECTIVE_END_DATE)),
                    builder.greaterThanOrEqualTo(root.get(EFFECTIVE_END_DATE), startDate)));
            if (endDate != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get(EFFECTIVE_START_DATE), endDate));
            }
            if (excludeMsrpId != null) {
                predicates.add(builder.notEqual(root.get(MSRP_ID), excludeMsrpId));
            }
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
