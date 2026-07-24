package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository.LocationExpiry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Tests the lot-aware {@link LotExpiryLotProvider} that activates FEFO (odoo-parity E3, #1047).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Lot expiry provider (FEFO activation)")
class LotExpiryLotProviderTest {

    private static final String SKU = "01960003-0000-7000-8000-000000000001";
    private static final UUID LOC_A = UUID.fromString("01960003-0000-7000-8000-0000000000a1");
    private static final UUID LOC_B = UUID.fromString("01960003-0000-7000-8000-0000000000a2");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 23);

    @Mock
    private InventoryStockSummaryRepository summaryRepository;

    private LotExpiryLotProvider provider;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        provider = new LotExpiryLotProvider(summaryRepository, clock);
    }

    private LocationExpiry row(UUID locationId, LocalDate expiry) {
        return new LocationExpiry() {
            @Override
            public UUID getLocationId() {
                return locationId;
            }

            @Override
            public LocalDate getEarliestExpiry() {
                return expiry;
            }
        };
    }

    @Test
    @DisplayName("hasExpiryData delegates to the repository")
    void hasExpiryData() {
        when(summaryRepository.hasExpiryData(SKU, TODAY)).thenReturn(true);
        assertThat(provider.hasExpiryData(SKU)).isTrue();
    }

    @Test
    @DisplayName("earliestExpiryByLocation maps LocalDate to start-of-day UTC per location")
    void earliestExpiryMapping() {
        when(summaryRepository.earliestExpiryByLocation(eq(SKU), any(), eq(TODAY)))
                .thenReturn(List.of(row(LOC_A, LocalDate.of(2026, 8, 1)), row(LOC_B, LocalDate.of(2026, 9, 15))));

        Map<UUID, Instant> result = provider.earliestExpiryByLocation(SKU, List.of(LOC_A, LOC_B));

        assertThat(result)
                .containsEntry(LOC_A, Instant.parse("2026-08-01T00:00:00Z"))
                .containsEntry(LOC_B, Instant.parse("2026-09-15T00:00:00Z"));
    }

    @Test
    @DisplayName("Empty location set short-circuits to an empty map")
    void emptyLocations() {
        assertThat(provider.earliestExpiryByLocation(SKU, List.of())).isEmpty();
    }
}
