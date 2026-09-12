package com.positivity.warranty.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.warranty.PostgresSliceTestBase;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.enums.ClaimType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * The combined claim search (PRD §8, {@link WarrantyClaimRepository#search}) and its
 * location-scoped sibling ({@link WarrantyClaimRepository#searchWithinLocations}, ADR-0061 §3,
 * #1885), against the real PostgreSQL schema.
 *
 * <p>These are database tests rather than mocked ones because what they guard is a property of the
 * database. Both searches are still one JPQL string of {@code (:param IS NULL OR column = :param)}
 * clauses and both are deliberately left that way. PostgreSQL rejects that shape at parse time only
 * when it has nothing to infer the placeholder's type from, and here it always has something: every
 * one of these parameters is compared with a column of its own type — three UUID columns and an
 * enum stored as varchar — so the server types the placeholder from the comparison whether a value
 * or a {@code setNull} arrives (issue #1891). The queries work, so they were not rewritten.
 *
 * <p>Two different changes would break them, and every case below is run with each optional filter
 * both supplied and absent because the two show up on opposite calls:
 *
 * <ul>
 *   <li>An optional filter of a type the driver leaves untyped — any {@code Instant},
 *       {@code LocalDate} or other temporal — would be rejected on <em>every</em> call, filters
 *       supplied or not, with {@code could not determine data type of parameter $n}.
 *   <li>An optional filter that never sits beside a column — one reaching PostgreSQL only through a
 *       function such as {@code LOWER(…)} or {@code UPPER(…)} — would be rejected only on the calls
 *       that omit it, with {@code function lower(bytea) does not exist}.
 * </ul>
 *
 * <p>Neither is visible to an H2-backed test, which is why these run on the real baseline.
 */
@DisplayName("Warranty claim search on PostgreSQL")
class WarrantyClaimRepositorySearchTest extends PostgresSliceTestBase {

    private static final UUID CUSTOMER_A = UUID.fromString("0199c000-0000-7000-8000-0000000000a1");
    private static final UUID CUSTOMER_B = UUID.fromString("0199c000-0000-7000-8000-0000000000b1");
    private static final UUID VEHICLE_A = UUID.fromString("0199d000-0000-7000-8000-0000000000a1");
    private static final UUID VEHICLE_B = UUID.fromString("0199d000-0000-7000-8000-0000000000b1");
    private static final UUID LOCATION_A = UUID.fromString("0199e000-0000-7000-8000-0000000000a1");
    private static final UUID LOCATION_B = UUID.fromString("0199e000-0000-7000-8000-0000000000b1");
    private static final Instant CREATED_AT = Instant.parse("2026-05-01T00:00:00Z");
    private static final Pageable PAGE = PageRequest.of(0, 25);

    @Autowired
    private WarrantyClaimRepository claims;

    /**
     * The slice does not enable JPA auditing, so the fixture pins the {@code NOT NULL}
     * {@code created_at}/{@code updated_at} columns itself. {@code claim_code}, {@code claim_type},
     * {@code status}, {@code customer_id} and {@code version} are {@code NOT NULL} in the baseline
     * too.
     */
    private WarrantyClaim claim(String code, UUID customerId, UUID vehicleId, ClaimStatus status, UUID locationId) {
        WarrantyClaim claim = WarrantyClaim.builder()
                .claimCode(code)
                .claimType(ClaimType.MANUFACTURER_DEFECT)
                .status(status)
                .customerId(customerId)
                .vehicleId(vehicleId)
                .locationId(locationId)
                .createdAt(CREATED_AT)
                .updatedAt(CREATED_AT)
                .build();
        return claims.saveAndFlush(claim);
    }

    @Test
    @DisplayName("an unfiltered search parses and returns every claim")
    void unfilteredSearchReturnsEveryClaim() {
        claim("WC-2026-000001", CUSTOMER_A, VEHICLE_A, ClaimStatus.DRAFT, LOCATION_A);
        claim("WC-2026-000002", CUSTOMER_B, VEHICLE_B, ClaimStatus.SUBMITTED, LOCATION_B);

        // Every optional filter absent: the call an untyped placeholder would reject outright.
        Page<WarrantyClaim> page = claims.search(null, null, null, null, PAGE);

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("each filter narrows the search on its own, with the other three absent")
    void eachFilterNarrowsTheSearchOnItsOwn() {
        WarrantyClaim wanted = claim("WC-2026-000010", CUSTOMER_A, VEHICLE_A, ClaimStatus.DRAFT, LOCATION_A);
        claim("WC-2026-000011", CUSTOMER_B, VEHICLE_B, ClaimStatus.SUBMITTED, LOCATION_B);

        assertThat(claims.search(CUSTOMER_A, null, null, null, PAGE).getContent())
                .containsExactly(wanted);
        assertThat(claims.search(null, VEHICLE_A, null, null, PAGE).getContent())
                .containsExactly(wanted);
        assertThat(claims.search(null, null, ClaimStatus.DRAFT, null, PAGE).getContent())
                .containsExactly(wanted);
        assertThat(claims.search(null, null, null, LOCATION_A, PAGE).getContent())
                .containsExactly(wanted);
    }

    @Test
    @DisplayName("the filters combine")
    void filtersCombine() {
        WarrantyClaim wanted = claim("WC-2026-000020", CUSTOMER_A, VEHICLE_A, ClaimStatus.DRAFT, LOCATION_A);
        claim("WC-2026-000021", CUSTOMER_A, VEHICLE_A, ClaimStatus.SUBMITTED, LOCATION_A);
        claim("WC-2026-000022", CUSTOMER_A, VEHICLE_B, ClaimStatus.DRAFT, LOCATION_A);

        assertThat(claims.search(CUSTOMER_A, VEHICLE_A, ClaimStatus.DRAFT, LOCATION_A, PAGE)
                        .getContent())
                .containsExactly(wanted);
    }

    @Test
    @DisplayName("a claim with no vehicle or location is returned by a search that does not filter on them")
    void claimWithoutVehicleOrLocationIsReturnedWhenNotFilteredOn() {
        // The null-column case, which is not the same question as the null-parameter one: an absent
        // filter must return the row, and a supplied filter must not.
        WarrantyClaim bare = claim("WC-2026-000030", CUSTOMER_A, null, ClaimStatus.DRAFT, null);

        assertThat(claims.search(CUSTOMER_A, null, null, null, PAGE).getContent())
                .containsExactly(bare);
        assertThat(claims.search(CUSTOMER_A, VEHICLE_A, null, null, PAGE).getContent())
                .isEmpty();
        assertThat(claims.search(CUSTOMER_A, null, null, LOCATION_A, PAGE).getContent())
                .isEmpty();
    }

    @Test
    @DisplayName("an unpaged search returns every match rather than failing")
    void unpagedSearchReturnsEveryMatch() {
        claim("WC-2026-000040", CUSTOMER_A, VEHICLE_A, ClaimStatus.DRAFT, LOCATION_A);
        claim("WC-2026-000041", CUSTOMER_B, VEHICLE_B, ClaimStatus.DRAFT, LOCATION_B);

        // Pageable.unpaged() reports a page size of zero, which PageRequest.of rejects; a search
        // that rebuilds the pageable has to carry the unpaged case through rather than throw.
        assertThat(claims.search(null, null, null, null, Pageable.unpaged()).getContent())
                .hasSize(2);
    }

    @Test
    @DisplayName("the location-scoped search parses with all three optional filters absent")
    void locationScopedSearchParsesWithEveryOptionalFilterAbsent() {
        WarrantyClaim inReach = claim("WC-2026-000050", CUSTOMER_A, VEHICLE_A, ClaimStatus.DRAFT, LOCATION_A);
        claim("WC-2026-000051", CUSTOMER_B, VEHICLE_B, ClaimStatus.DRAFT, LOCATION_B);

        Page<WarrantyClaim> page = claims.searchWithinLocations(null, null, null, Set.of(LOCATION_A), PAGE);

        assertThat(page.getContent()).containsExactly(inReach);
    }

    @Test
    @DisplayName("the location-scoped search narrows on each optional filter too")
    void locationScopedSearchNarrowsOnEachOptionalFilter() {
        WarrantyClaim wanted = claim("WC-2026-000060", CUSTOMER_A, VEHICLE_A, ClaimStatus.DRAFT, LOCATION_A);
        claim("WC-2026-000061", CUSTOMER_B, VEHICLE_B, ClaimStatus.SUBMITTED, LOCATION_A);

        List<UUID> reach = List.of(LOCATION_A, LOCATION_B);
        assertThat(claims.searchWithinLocations(CUSTOMER_A, null, null, reach, PAGE)
                        .getContent())
                .containsExactly(wanted);
        assertThat(claims.searchWithinLocations(null, VEHICLE_A, null, reach, PAGE)
                        .getContent())
                .containsExactly(wanted);
        assertThat(claims.searchWithinLocations(null, null, ClaimStatus.DRAFT, reach, PAGE)
                        .getContent())
                .containsExactly(wanted);
    }

    @Test
    @DisplayName("a claim with no location is outside every reach, so a location-scoped caller never sees it")
    void claimWithoutLocationIsOutsideEveryReach() {
        claim("WC-2026-000070", CUSTOMER_A, VEHICLE_A, ClaimStatus.DRAFT, null);

        // Fail closed: the same rule the gate applies to an unknown location.
        assertThat(claims.searchWithinLocations(null, null, null, Set.of(LOCATION_A, LOCATION_B), PAGE)
                        .getContent())
                .isEmpty();
    }
}
