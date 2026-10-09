package com.positivity.tax.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.tax.common.enums.ExemptionReasonCode;
import com.positivity.tax.internal.dto.TaxRegistrationCreateRequest;
import com.positivity.tax.internal.entity.ExemptionCertificate;
import com.positivity.tax.internal.entity.TaxRegistration;
import com.positivity.tax.internal.enums.ExemptionCertificateStatus;
import com.positivity.tax.internal.repository.ExemptionCertificateRepository;
import com.positivity.tax.internal.repository.TaxRegistrationRepository;
import com.positivity.tax.internal.service.TaxRegistrationService;
import com.positivity.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Proves the isolation, not just the mapping (plan R-B7): a row written as tenant A is invisible to
 * tenant B through the repository (Hibernate's {@code @TenantId} filter) and through a raw {@code
 * JdbcTemplate} on the same pool (row-level security alone), and an unbound connection can neither
 * read nor write a scoped table. The exemption certificate is the subject: the simplest scoped table here.
 */
@DisplayName("Tenant isolation on Postgres (ADR-0062, pos-tax)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    @Autowired
    private ExemptionCertificateRepository rows;

    @Autowired
    private TaxRegistrationRepository registrations;

    @Autowired
    private TaxRegistrationService service;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aRowWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        UUID id = asTenant(TENANT_A, () -> rows.saveAndFlush(certificate()).getId());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        asTenant(TENANT_A, () -> {
            assertThat(rows.findById(id))
                    .as("owner reads through the repository")
                    .isPresent();
            assertThat(rows.findById(id).orElseThrow().getTenantId()).isEqualTo(TENANT_A);
            assertThat(countById(jdbc, id)).as("owner reads through raw SQL").isEqualTo(1);
        });

        asTenant(TENANT_B, () -> {
            assertThat(rows.findById(id))
                    .as("Hibernate filter hides the other tenant's row")
                    .isEmpty();
            assertThat(countById(jdbc, id)).as("RLS hides it from raw SQL too").isZero();
            assertThat(jdbc.update("UPDATE exemption_certificate SET customer_id = 'hijacked' WHERE id = ?", id))
                    .as("RLS makes the row unreachable for UPDATE")
                    .isZero();
        });

        // Unbound: the pool RESETs app.current_tenant, so pos_app sees an empty table and cannot insert.
        assertThat(countById(jdbc, id)).isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO exemption_certificate (id, customer_id, reason_code, effective_from, status,"
                                + " created_at, updated_at) VALUES (?, 'nobody', 'RESALE', now(), 'ACTIVE', now(), now())",
                        UUID.randomUUID()))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);

        asTenant(
                TENANT_A,
                () -> assertThat(rows.findById(id).orElseThrow().getCustomerId())
                        .as("tenant B's UPDATE touched nothing")
                        .isEqualTo("cust-1"));
    }

    @Test
    @DisplayName("CAP:550 S32c AC 1: tenant B never sees tenant A's tax registration, nor can it end it")
    void aTaxRegistrationIsInvisibleToAnotherTenant() {
        UUID id = asTenant(
                TENANT_A,
                () -> registrations
                        .saveAndFlush(TaxRegistration.builder()
                                .countryCode("ZZ")
                                .regime("R_1")
                                .registrationNumber("ZZ12345")
                                .jurisdictionCode("ZZ")
                                .effectiveFrom(LocalDate.of(2026, 1, 1))
                                .createdBy("01990000-0000-7000-8000-0000000000e1")
                                .updatedBy("01990000-0000-7000-8000-0000000000e1")
                                .build())
                        .getId());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        asTenant(
                TENANT_A,
                () -> assertThat(registrations.findByCountryCodeAndRegime("ZZ", "R_1"))
                        .extracting(TaxRegistration::getId)
                        .containsExactly(id));
        asTenant(TENANT_B, () -> {
            assertThat(registrations.findById(id)).isEmpty();
            assertThat(registrations.findByCountryCodeAndRegime("ZZ", "R_1")).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tax_registration WHERE id = ?", Integer.class, id))
                    .isZero();
            assertThat(jdbc.update("UPDATE tax_registration SET effective_to = DATE '2026-01-31' WHERE id = ?", id))
                    .isZero();
        });
    }

    @Test
    @DisplayName("CAP:550 S32c: tenant B reusing tenant A's requestId gets a fresh write, not A's result")
    void aRequestIdIsScopedToItsTenant() {
        UUID requestId = UUID.randomUUID();
        UUID actor = UUID.fromString("01990000-0000-7000-8000-0000000000e1");
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(actor.toString(), null, List.of()));
        try {
            UUID first = asTenant(
                    TENANT_A,
                    () -> service.create(request(requestId)).registration().registrationId());
            TaxRegistrationService.WriteResult second = asTenant(TENANT_B, () -> service.create(request(requestId)));

            assertThat(second.replayed()).isFalse();
            assertThat(second.registration().registrationId()).isNotEqualTo(first);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static TaxRegistrationCreateRequest request(UUID requestId) {
        return new TaxRegistrationCreateRequest(
                "CA",
                "GST_HST",
                "123456789RT0001",
                LocalDate.of(2026, 1, 1),
                null,
                "Registered with the tax authority",
                requestId);
    }

    private static ExemptionCertificate certificate() {
        return ExemptionCertificate.builder()
                .customerId("cust-1")
                .reasonCode(ExemptionReasonCode.RESALE)
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .status(ExemptionCertificateStatus.ACTIVE)
                .build();
    }

    private static int countById(JdbcTemplate jdbc, UUID id) {
        Integer count =
                jdbc.queryForObject("SELECT count(*) FROM exemption_certificate WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }
}
