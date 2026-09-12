package com.positivity.tax.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tax.PostgresSliceTestBase;
import com.positivity.tax.common.enums.ExemptionReasonCode;
import com.positivity.tax.internal.entity.ExemptionCertificate;
import com.positivity.tax.internal.enums.ExemptionCertificateStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;

/**
 * The certificate lookup ({@link ExemptionCertificateRepository#findActiveForCustomer}) against the
 * real PostgreSQL schema, exercising every combination of its optional filters.
 *
 * <p>This is a database test rather than a mocked one because what broke this query was a property
 * of the database. It was one JPQL string of {@code (:param IS NULL OR …)} clauses, and one of
 * those placeholders — {@code stateScope} — appeared only in {@code IS NULL} and inside
 * {@code upper(…)}, never beside a column: Hibernate had no type to infer, bound it as an opaque
 * binary, and PostgreSQL rejected the statement with {@code function upper(bytea) does not exist}
 * every time the scope was absent. Since {@code destinationAddress.regionCode} is an optional field
 * of a tax calculation request, that was most calls: every {@code POST /v1/tax/calculate} carrying
 * an exemption claim without a region code returned 500, while the H2-backed tests of the same
 * query stayed green. See {@link ActiveCertificateSearch} for the fix and for how this variant
 * differs from the temporal one of issue #1891.
 *
 * <p>Note that {@code date} is a {@code LocalDate} and causes no trouble: it is compared with a
 * column, never asked {@code IS NULL}, so its type is inferred from the comparison.
 */
@DisplayName("Exemption certificate lookup on PostgreSQL")
class ExemptionCertificateRepositoryPostgresTest extends PostgresSliceTestBase {

    private static final String CUSTOMER = "cust-1";
    private static final LocalDate JAN = LocalDate.of(2026, 1, 1);
    private static final LocalDate JUN = LocalDate.of(2026, 6, 1);
    private static final LocalDate DEC = LocalDate.of(2026, 12, 1);

    @Autowired
    private ExemptionCertificateRepository certificates;

    private ExemptionCertificate certificate(
            String stateScope, ExemptionReasonCode reasonCode, LocalDate effectiveFrom, LocalDate expiresAt) {
        return certificate(
                CUSTOMER, stateScope, reasonCode, effectiveFrom, expiresAt, ExemptionCertificateStatus.ACTIVE);
    }

    private ExemptionCertificate certificate(
            String customerId,
            String stateScope,
            ExemptionReasonCode reasonCode,
            LocalDate effectiveFrom,
            LocalDate expiresAt,
            ExemptionCertificateStatus status) {
        return certificates.saveAndFlush(ExemptionCertificate.builder()
                .customerId(customerId)
                .stateScope(stateScope)
                .reasonCode(reasonCode)
                .effectiveFrom(effectiveFrom)
                .expiresAt(expiresAt)
                .status(status)
                .build());
    }

    @Test
    @DisplayName("an unfiltered lookup returns the customer's active certificate")
    void unfilteredLookupReturnsTheActiveCertificate() {
        ExemptionCertificate resale = certificate(null, ExemptionReasonCode.RESALE, JAN, null);

        assertThat(certificates.findActiveForCustomer(CUSTOMER, null, null, JUN, Limit.of(10)))
                .containsExactly(resale);
    }

    @Test
    @DisplayName("a state-scoped certificate matches its own scope and an unscoped one matches any")
    void stateScopeNarrowsTheLookup() {
        ExemptionCertificate california = certificate("CA", ExemptionReasonCode.RESALE, JAN, null);

        assertThat(certificates.findActiveForCustomer(CUSTOMER, "CA", null, JUN, Limit.of(10)))
                .containsExactly(california);
        assertThat(certificates.findActiveForCustomer(CUSTOMER, "TX", null, JUN, Limit.of(10)))
                .isEmpty();
        // Case-insensitively: the request carries whatever the caller typed.
        assertThat(certificates.findActiveForCustomer(CUSTOMER, "ca", null, JUN, Limit.of(10)))
                .containsExactly(california);
    }

    @Test
    @DisplayName("a certificate with no scope of its own applies in every state")
    void unscopedCertificateAppliesEverywhere() {
        ExemptionCertificate anywhere = certificate(null, ExemptionReasonCode.RESALE, JAN, null);

        assertThat(certificates.findActiveForCustomer(CUSTOMER, "TX", null, JUN, Limit.of(10)))
                .containsExactly(anywhere);
    }

    @Test
    @DisplayName("the reason filter narrows the lookup on its own")
    void reasonCodeNarrowsTheLookup() {
        ExemptionCertificate resale = certificate(null, ExemptionReasonCode.RESALE, JAN, null);
        certificate(null, ExemptionReasonCode.GOVERNMENT, JAN, null);

        assertThat(certificates.findActiveForCustomer(CUSTOMER, null, ExemptionReasonCode.RESALE, JUN, Limit.of(10)))
                .containsExactly(resale);
    }

    @Test
    @DisplayName("the filters combine")
    void filtersCombine() {
        ExemptionCertificate wanted = certificate("CA", ExemptionReasonCode.RESALE, JAN, null);
        certificate("CA", ExemptionReasonCode.GOVERNMENT, JAN, null);
        certificate("TX", ExemptionReasonCode.RESALE, JAN, null);

        assertThat(certificates.findActiveForCustomer(CUSTOMER, "CA", ExemptionReasonCode.RESALE, JUN, Limit.of(10)))
                .containsExactly(wanted);
    }

    @Test
    @DisplayName("the date window is inclusive at both ends and excludes what has lapsed")
    void dateWindowIsInclusive() {
        certificate(null, ExemptionReasonCode.RESALE, JUN, DEC);

        assertThat(certificates.findActiveForCustomer(CUSTOMER, null, null, JUN, Limit.of(10)))
                .hasSize(1);
        assertThat(certificates.findActiveForCustomer(CUSTOMER, null, null, DEC, Limit.of(10)))
                .hasSize(1);
        assertThat(certificates.findActiveForCustomer(CUSTOMER, null, null, JAN, Limit.of(10)))
                .isEmpty();
        assertThat(certificates.findActiveForCustomer(CUSTOMER, null, null, DEC.plusDays(1), Limit.of(10)))
                .isEmpty();
    }

    @Test
    @DisplayName("only ACTIVE certificates of the named customer are returned")
    void onlyActiveCertificatesOfTheNamedCustomer() {
        certificate(CUSTOMER, null, ExemptionReasonCode.RESALE, JAN, null, ExemptionCertificateStatus.REVOKED);
        certificate("someone-else", null, ExemptionReasonCode.RESALE, JAN, null, ExemptionCertificateStatus.ACTIVE);

        assertThat(certificates.findActiveForCustomer(CUSTOMER, null, null, JUN, Limit.of(10)))
                .isEmpty();
    }

    @Test
    @DisplayName("the limit the caller asks for is honored")
    void limitIsHonored() {
        certificate(null, ExemptionReasonCode.RESALE, JAN, null);
        certificate(null, ExemptionReasonCode.GOVERNMENT, JAN, null);

        assertThat(certificates.findActiveForCustomer(CUSTOMER, null, null, JUN, Limit.of(1)))
                .hasSize(1);
    }

    @Test
    @DisplayName("an unlimited lookup returns every match rather than failing")
    void unlimitedLookupReturnsEveryMatch() {
        certificate(null, ExemptionReasonCode.RESALE, JAN, null);
        certificate(null, ExemptionReasonCode.GOVERNMENT, JAN, null);

        // Limit.unlimited() reports a max of -1, which the fluent query rejects; a search that
        // rebuilds the query has to carry the unlimited case through rather than throw.
        assertThat(certificates.findActiveForCustomer(CUSTOMER, null, null, JUN, Limit.unlimited()))
                .hasSize(2);
    }

    @Test
    @DisplayName("the most recently effective certificate comes first")
    void mostRecentlyEffectiveComesFirst() {
        ExemptionCertificate older = certificate(null, ExemptionReasonCode.RESALE, JAN, null);
        ExemptionCertificate newer = certificate(null, ExemptionReasonCode.GOVERNMENT, JUN, null);

        assertThat(certificates.findActiveForCustomer(CUSTOMER, null, null, JUN, Limit.of(10)))
                .containsExactly(newer, older);
    }
}
