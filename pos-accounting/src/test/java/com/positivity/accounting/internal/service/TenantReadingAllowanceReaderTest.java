package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.BillIntakeReadingAllowance;
import com.positivity.accounting.internal.repository.BillIntakeReadingAllowanceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The tenant allowance read (#2619): the stored value, else empty (S26 fails closed on empty). */
@DisplayName("TenantReadingAllowanceReader")
class TenantReadingAllowanceReaderTest {

    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-0000000000b2");
    private static final Instant EFFECTIVE = Instant.parse("2026-10-17T00:00:00Z");

    private final BillIntakeReadingAllowanceRepository allowances = mock(BillIntakeReadingAllowanceRepository.class);
    private final TenantReadingAllowanceReader reader = new TenantReadingAllowanceReader(allowances);

    @Test
    @DisplayName("a stored allowance is returned with its tier, amount, currency and effective instant")
    void storedIsPresent() {
        BillIntakeReadingAllowance row = new BillIntakeReadingAllowance();
        row.setReadingAllowanceId(TENANT);
        row.setTierCode("PRO");
        row.setAmount(new BigDecimal("60.0000"));
        row.setCurrency("USD");
        row.setEffectiveAt(EFFECTIVE);
        when(allowances.findById(TENANT)).thenReturn(Optional.of(row));

        assertThat(reader.allowanceOf(TENANT)).hasValueSatisfying(allowance -> {
            assertThat(allowance.tierCode()).isEqualTo("PRO");
            assertThat(allowance.amount()).isEqualByComparingTo("60");
            assertThat(allowance.currencyCode()).isEqualTo("USD");
            assertThat(allowance.effectiveAt()).isEqualTo(EFFECTIVE);
        });
    }

    @Test
    @DisplayName("a tenant with no row has no known allowance")
    void noneIsEmpty() {
        when(allowances.findById(TENANT)).thenReturn(Optional.empty());

        assertThat(reader.allowanceOf(TENANT)).isEmpty();
    }
}
