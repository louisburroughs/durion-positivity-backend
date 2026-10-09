package com.positivity.order.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.order.PostgresSliceTestBase;
import com.positivity.order.internal.entity.ExtTaxRegistration;
import com.positivity.order.internal.service.TaxRegistrationReplica;
import com.positivity.order.internal.service.TaxRegistrationReplicaImpl;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * CAP:550 S32c (AW49): pos-order reads its copy of the tenant's tax registrations as of a business date, both ends
 * inclusive, on PostgreSQL. Made-up country and regime codes; not tax law.
 */
@DisplayName("ext_tax_registration as-of read on PostgreSQL (CAP:550 S32c)")
class ExtTaxRegistrationRepositoryTest extends PostgresSliceTestBase {

    private static final UUID ENDED = UUID.fromString("01990000-0000-7000-8000-0000000000a1");
    private static final UUID CURRENT = UUID.fromString("01990000-0000-7000-8000-0000000000a2");
    private static final UUID OTHER_REGIME = UUID.fromString("01990000-0000-7000-8000-0000000000a3");

    @Autowired
    private ExtTaxRegistrationRepository registrations;

    private void row(UUID id, String regime, String jurisdiction, LocalDate from, LocalDate to) {
        registrations.saveAndFlush(ExtTaxRegistration.builder()
                .registrationId(id)
                .countryCode("ZZ")
                .regime(regime)
                .jurisdictionCode(jurisdiction)
                .effectiveFrom(from)
                .effectiveTo(to)
                .aggregateVersion(0)
                .syncedAt(Instant.parse("2026-10-08T12:00:00Z"))
                .build());
    }

    @Test
    @DisplayName("the registration in effect on a date is found, both ends inclusive; none before the first")
    void readsAsOfABusinessDate() {
        row(ENDED, "R_1", "ZZ", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        row(CURRENT, "R_1", "ZZ", LocalDate.of(2026, 1, 1), null);
        row(OTHER_REGIME, "R_2", "Z1", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        TaxRegistrationReplica replica = new TaxRegistrationReplicaImpl(registrations);

        assertThat(replica.inEffectOn("ZZ", "R_1", LocalDate.of(2025, 12, 31)))
                .map(TaxRegistrationReplica.Registration::registrationId)
                .contains(ENDED);
        assertThat(replica.inEffectOn("ZZ", "R_1", LocalDate.of(2026, 1, 1)))
                .map(TaxRegistrationReplica.Registration::registrationId)
                .contains(CURRENT);
        assertThat(replica.inEffectOn("ZZ", "R_1", LocalDate.of(2024, 12, 31))).isEmpty();
        assertThat(replica.inEffectOn("ZZ", "R_2", LocalDate.of(2026, 3, 31)))
                .map(TaxRegistrationReplica.Registration::jurisdictionCode)
                .contains("Z1");
        assertThat(replica.inEffectOn("ZZ", "R_2", LocalDate.of(2026, 4, 1))).isEmpty();
    }
}
