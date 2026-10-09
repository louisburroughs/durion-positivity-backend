package com.positivity.accounting.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.PostgresIntegrationTestBase;
import com.positivity.accounting.internal.entity.ExtTaxRegistration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * CAP:550 S32c (AW49): accounting's copy of the tenant's tax registrations, read as of a date on PostgreSQL: both ends
 * inclusive, in country, regime and start order. Made-up codes; not tax law.
 */
@Transactional
@DisplayName("ext_tax_registration as-of read on PostgreSQL (CAP:550 S32c)")
class ExtTaxRegistrationRepositoryPostgresTest extends PostgresIntegrationTestBase {

    private static final UUID ENDED = UUID.fromString("01990000-0000-7000-8000-0000000000c1");
    private static final UUID CURRENT = UUID.fromString("01990000-0000-7000-8000-0000000000c2");
    private static final UUID OTHER_REGIME = UUID.fromString("01990000-0000-7000-8000-0000000000c3");
    private static final UUID OTHER_COUNTRY = UUID.fromString("01990000-0000-7000-8000-0000000000c4");

    @Autowired
    private ExtTaxRegistrationRepository registrations;

    private void row(UUID id, String country, String regime, LocalDate from, LocalDate to) {
        registrations.saveAndFlush(ExtTaxRegistration.builder()
                .registrationId(id)
                .countryCode(country)
                .regime(regime)
                .registrationNumber("ZZ12345")
                .jurisdictionCode(country)
                .effectiveFrom(from)
                .effectiveTo(to)
                .aggregateVersion(0)
                .changedAt(Instant.parse("2026-10-08T12:00:00Z"))
                .syncedAt(Instant.parse("2026-10-08T12:00:01Z"))
                .build());
    }

    @Test
    @DisplayName("in effect on a date: both ends inclusive, ordered by country, regime and start")
    void readsAsOfADate() {
        row(OTHER_REGIME, "ZZ", "R_2", LocalDate.of(2025, 6, 1), null);
        row(ENDED, "ZZ", "R_1", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        row(CURRENT, "ZZ", "R_1", LocalDate.of(2026, 1, 1), null);
        row(OTHER_COUNTRY, "YY", "R_9", LocalDate.of(2025, 1, 1), null);

        assertThat(registrations.findInEffectOn(LocalDate.of(2025, 12, 31)))
                .extracting(ExtTaxRegistration::getRegistrationId)
                .containsExactly(OTHER_COUNTRY, ENDED, OTHER_REGIME);
        assertThat(registrations.findInEffectOn(LocalDate.of(2026, 1, 1)))
                .extracting(ExtTaxRegistration::getRegistrationId)
                .containsExactly(OTHER_COUNTRY, CURRENT, OTHER_REGIME);
        assertThat(registrations.findInEffectOn(LocalDate.of(2024, 12, 31))).isEmpty();
        assertThat(registrations.findAllByOrderByCountryCodeAscRegimeAscEffectiveFromAsc())
                .extracting(ExtTaxRegistration::getRegistrationId)
                .containsExactly(OTHER_COUNTRY, ENDED, CURRENT, OTHER_REGIME);
    }
}
