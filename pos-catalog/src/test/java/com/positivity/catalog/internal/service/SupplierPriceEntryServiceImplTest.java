package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.dto.SupplierPriceEntryDto;
import com.positivity.catalog.internal.entity.SupplierPriceEntryEntity;
import com.positivity.catalog.internal.entity.SupplierPriceImportEntity;
import com.positivity.catalog.internal.repository.SupplierPriceEntryRepository;
import com.positivity.catalog.internal.repository.SupplierPriceImportRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Supplier price entry reads (#1308, ADR-0053 §2)")
class SupplierPriceEntryServiceImplTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d");
    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private SupplierPriceEntryRepository priceEntryRepository;

    @Mock
    private SupplierPriceImportRepository priceImportRepository;

    private SupplierPriceEntryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SupplierPriceEntryServiceImpl(priceEntryRepository, priceImportRepository, CLOCK);
    }

    private static SupplierPriceEntryEntity entry(LocalDate effectiveFrom, String netPrice) {
        return SupplierPriceEntryEntity.builder()
                .id(UUID.randomUUID())
                .vendorProfileId(PROFILE_ID)
                .supplierRef("michelin-eu")
                .productId(PRODUCT_ID)
                .matchMethod("EAN")
                .articleEan("3528709999083")
                .supplierArticleCode("999908")
                .buyerAccountNumber("30012456")
                .countryCode("SE")
                .currency("SEK")
                .netPrice(new BigDecimal(netPrice))
                .effectiveFrom(effectiveFrom)
                .importManifestId(UUID.randomUUID())
                .fetchedAt(Instant.parse("2026-08-14T08:00:00Z"))
                .build();
    }

    @Test
    void takesTheMostApplicableEntryTheQueryReturnsFirst() {
        when(priceEntryRepository.findApplicable(
                        eq(PRODUCT_ID), isNull(), eq("SE"), eq("SEK"), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(List.of(entry(LocalDate.of(2026, 2, 1), "90.00")));

        Optional<SupplierPriceEntryDto> latest = service.findLatest(PRODUCT_ID, null, "SE", "SEK", null);

        assertThat(latest).isPresent();
        assertThat(latest.get().netPrice()).isEqualByComparingTo(new BigDecimal("90.00"));
        assertThat(latest.get().effectiveFrom()).isEqualTo(LocalDate.of(2026, 2, 1));
    }

    @Test
    void evaluatesTodayWhenNoDayIsGiven() {
        when(priceEntryRepository.findApplicable(
                        eq(PRODUCT_ID), isNull(), eq("SE"), eq("SEK"), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(List.of(entry(LocalDate.of(2026, 2, 1), "90.00")));

        service.findLatest(PRODUCT_ID, null, "SE", "SEK", null);

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        org.mockito.Mockito.verify(priceEntryRepository)
                .findApplicable(eq(PRODUCT_ID), isNull(), eq("SE"), eq("SEK"), captor.capture(), any(Pageable.class));
        assertThat(captor.getValue()).isEqualTo(LocalDate.of(2026, 8, 14));
    }

    @Test
    void answersAPastDayWhenOneIsGiven() {
        when(priceEntryRepository.findApplicable(
                        eq(PRODUCT_ID),
                        isNull(),
                        eq("SE"),
                        eq("SEK"),
                        eq(LocalDate.of(2026, 3, 1)),
                        any(Pageable.class)))
                .thenReturn(List.of(entry(LocalDate.of(2026, 2, 1), "90.00")));

        assertThat(service.findLatest(PRODUCT_ID, null, "SE", "SEK", LocalDate.of(2026, 3, 1)))
                .isPresent();
    }

    @Test
    void reportsAbsenceRatherThanZeroWhenNoVendorPriceApplies() {
        when(priceEntryRepository.findApplicable(
                        eq(PRODUCT_ID), isNull(), eq("SE"), eq("SEK"), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(service.findLatest(PRODUCT_ID, null, "SE", "SEK", null)).isEmpty();
    }

    @Test
    void narrowsToOneVendorWhenAProfileIsGiven() {
        when(priceEntryRepository.findApplicable(
                        eq(PRODUCT_ID), eq(PROFILE_ID), eq("SE"), eq("SEK"), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(List.of(entry(LocalDate.of(2026, 2, 1), "90.00")));

        assertThat(service.findLatest(PRODUCT_ID, PROFILE_ID, "SE", "SEK", null))
                .isPresent();
    }

    @Test
    void returnsSupersededEntriesInHistory() {
        Page<SupplierPriceEntryEntity> page = new PageImpl<>(
                List.of(entry(LocalDate.of(2026, 2, 1), "90.00"), entry(LocalDate.of(2026, 1, 1), "85.00")));
        when(priceEntryRepository.findHistory(eq(PRODUCT_ID), isNull(), any(Pageable.class)))
                .thenReturn(page);

        Page<SupplierPriceEntryDto> history = service.findHistory(PRODUCT_ID, null, PageRequest.of(0, 50));

        assertThat(history.getContent()).hasSize(2);
        assertThat(history.getContent().getFirst().effectiveFrom()).isEqualTo(LocalDate.of(2026, 2, 1));
    }

    @Test
    void preservesTheRepositoryOrderingOfIncompleteImports() {
        // The repository orders by updatedAt DESC; the service must pass that through rather than
        // re-sorting, so the fixture arrives newest-first and must leave in the same order.
        when(priceImportRepository.findByStatusOrderByUpdatedAtDesc(SupplierPriceImportEntity.STATUS_INCOMPLETE))
                .thenReturn(List.of(
                        SupplierPriceImportEntity.builder()
                                .importManifestId(UUID.randomUUID())
                                .vendorProfileId(PROFILE_ID)
                                .status(SupplierPriceImportEntity.STATUS_INCOMPLETE)
                                .chunksApplied(2)
                                .expectedChunkCount(4)
                                .updatedAt(Instant.parse("2026-08-14T10:00:00Z"))
                                .build(),
                        SupplierPriceImportEntity.builder()
                                .importManifestId(UUID.randomUUID())
                                .vendorProfileId(PROFILE_ID)
                                .status(SupplierPriceImportEntity.STATUS_INCOMPLETE)
                                .chunksApplied(1)
                                .expectedChunkCount(3)
                                .updatedAt(Instant.parse("2026-08-13T10:00:00Z"))
                                .build()));

        assertThat(service.findIncompleteImports())
                .extracting(dto -> dto.updatedAt())
                .containsExactly(Instant.parse("2026-08-14T10:00:00Z"), Instant.parse("2026-08-13T10:00:00Z"));
    }
}
