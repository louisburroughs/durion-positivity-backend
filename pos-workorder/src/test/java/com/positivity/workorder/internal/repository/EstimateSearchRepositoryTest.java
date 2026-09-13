package com.positivity.workorder.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.workorder.PostgresSliceTestBase;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.enums.EstimateStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * The estimate finder ({@link EstimateRepository#searchByQuery}) backing
 * {@code GET /v1/workexec/estimates/search}, against the real PostgreSQL baseline.
 *
 * <p>A database test rather than a mocked one because what it guards is a property of the database.
 * The finder is one JPQL string whose last disjunct is {@code (:idQuery IS NOT NULL AND e.id =
 * :idQuery)} — the same inference question as the {@code IS NULL} filters elsewhere in this module,
 * asked in the other direction, and one that a UUID answers: pgjdbc gives it a concrete type OID for
 * a value and for {@code setNull} alike, so the statement parses whether or not the query text
 * happened to be a UUID (issue #1891). The query works, so it was not rewritten.
 *
 * <p>What this pins is that it goes on working. Two changes would break it, and each case below
 * exercises the shape that would catch one. Making any filter optional and temporal would reject the
 * statement at parse time on every call. Letting {@code q} through as null would reject it only on
 * the calls that omit the term: {@code q} is the one parameter here never compared with a column —
 * it reaches PostgreSQL only inside {@code LOWER(CONCAT(…))} — so a null leaves the server resolving
 * {@code unknown || unknown} as {@code bytea} and answering {@code function lower(bytea) does not
 * exist}. That is why the controller only takes this path for a non-blank query, and why every case
 * here supplies a term. Both failure modes are invisible to an H2-backed test.
 *
 * <p>Each optional filter is exercised both ways — supplied and null — because the two failure
 * modes show up on opposite calls.
 */
@DisplayName("Estimate finder on PostgreSQL")
class EstimateSearchRepositoryTest extends PostgresSliceTestBase {

    private static final Instant CREATED_AT = Instant.parse("2026-06-01T00:00:00Z");
    private static final UUID CUSTOMER_A = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID CUSTOMER_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final Pageable PAGE = PageRequest.of(0, 25);

    /** Mirrors the service: JPQL IN needs a non-empty collection, so an unresolved name leg sends a sentinel. */
    private static final List<UUID> NO_CUSTOMER = List.of(new UUID(0, 0));

    @Autowired
    private EstimateRepository estimates;

    /**
     * The slice does not enable JPA auditing, so the fixture pins the {@code NOT NULL}
     * {@code created_at}/{@code updated_at} columns itself; {@code created_by_id} is
     * {@code NOT NULL} in the baseline too.
     */
    private Estimate estimate(String number, UUID customerId) {
        Estimate estimate = new Estimate();
        estimate.setEstimateNumber(number);
        estimate.setCustomerId(customerId);
        estimate.setStatus(EstimateStatus.DRAFT);
        estimate.setCreatedById("advisor-1");
        estimate.setCreatedAt(CREATED_AT);
        estimate.setUpdatedAt(CREATED_AT);
        return estimates.saveAndFlush(estimate);
    }

    @Test
    @DisplayName("a non-UUID query leaves idQuery null and still matches on the estimate number")
    void nonUuidQueryMatchesOnTheEstimateNumber() {
        Estimate wanted = estimate("EST-2026-ABC123", CUSTOMER_A);
        estimate("EST-2026-ZZZ999", CUSTOMER_A);

        // idQuery is null here: the call shape an untyped placeholder would have rejected outright.
        Page<Estimate> page = estimates.searchByQuery("abc123", NO_CUSTOMER, null, PAGE);

        assertThat(page.getContent()).containsExactly(wanted);
    }

    @Test
    @DisplayName("a query that parses as a UUID matches that estimate by id")
    void uuidQueryMatchesTheEstimateById() {
        Estimate wanted = estimate("EST-2026-BYID", CUSTOMER_A);
        estimate("EST-2026-OTHER", CUSTOMER_A);

        Page<Estimate> page = estimates.searchByQuery(wanted.getId().toString(), NO_CUSTOMER, wanted.getId(), PAGE);

        assertThat(page.getContent()).containsExactly(wanted);
    }

    @Test
    @DisplayName("customer ids resolved from a name search match as an alternative to the number")
    void resolvedCustomerIdsMatchAsAnAlternative() {
        estimate("EST-2026-ONE", CUSTOMER_A);
        Estimate wanted = estimate("EST-2026-TWO", CUSTOMER_B);

        Page<Estimate> page = estimates.searchByQuery("no-such-number", List.of(CUSTOMER_B), null, PAGE);

        assertThat(page.getContent()).containsExactly(wanted);
    }
}
