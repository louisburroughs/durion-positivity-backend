package com.positivity.workorder.service;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.positivity.workorder.internal.dto.EstimateSummaryResponse;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.service.EstimateServiceImpl;

/**
 * Unit tests for the paginated estimate search behaviour introduced in
 * Story #15.
 *
 * <p>
 * Covers the acceptance criteria for
 * {@code EstimateService#searchEstimates(UUID, UUID, Pageable)}. The
 * implementation is complete in {@code EstimateServiceImpl} and all 8 tests
 * pass (GREEN). Mocks are wired once in each test so assertions remain
 * independent and deterministic.
 *
 * Issue: #15 (CAP-248)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EstimateSearchServiceTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Spy
        Clock clock = TEST_CLOCK;

        @Mock
        private EstimateRepository estimateRepository;

        /**
         * Required for {@code @InjectMocks} constructor wiring, but not called in the
         * search path (line items are intentionally omitted from paginated search
         * summaries — {@code partItems}/{@code laborItems} return empty lists). LENIENT
         * strictness prevents unused-stub failures.
         */
        @Mock
        private EstimateItemRepository estimateItemRepository;

        @InjectMocks
        private EstimateServiceImpl estimateService;

        // ------------------------------------------------------------------
        // Shared fixture constants
        // ------------------------------------------------------------------

        private static final UUID CUSTOMER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        private static final UUID VEHICLE_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
        private static final UUID LOCATION_C = UUID.fromString("cccccccc-0000-0000-0000-000000000001");

        // ------------------------------------------------------------------
        // Fixture builders
        // ------------------------------------------------------------------

        /**
         * Build a minimal {@link Estimate} entity for injection into mock returns.
         *
         * @param id         unique estimate ID
         * @param customerId owning customer
         * @param vehicleId  linked vehicle
         * @param status     lifecycle status
         * @return fully populated estimate entity
         */
        private Estimate buildEstimate(UUID id, UUID customerId, UUID vehicleId, EstimateStatus status) {
                return Estimate.builder()
                                .id(id)
                                .estimateNumber("EST-2024-" + id.toString().substring(0, 4))
                                .customerId(customerId)
                                .vehicleId(vehicleId)
                                .locationId(LOCATION_C)
                                .status(status)
                                .subtotal(BigDecimal.valueOf(100))
                                .taxAmount(BigDecimal.valueOf(8))
                                .total(BigDecimal.valueOf(108))
                                .currencyUomId("USD")
                                .createdAt(Instant.now(TEST_CLOCK))
                                .expiresAt(LocalDateTime.now(TEST_CLOCK).plusDays(30))
                                .build();
        }

        // ------------------------------------------------------------------
        // AC1 – GET /v1/workexec/estimates/search?customerId={id}
        // returns paginated estimates for that customer
        // ------------------------------------------------------------------

        /**
         * When the repository returns a single estimate for the given customer, the
         * service result contains exactly that estimate with the correct customer ID.
         */
        @Test
        void searchEstimates_byCustomerId_returnsEstimatesForThatCustomer() {
                UUID estimateId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                Estimate estimate = buildEstimate(estimateId, CUSTOMER_A, VEHICLE_B, EstimateStatus.OPEN);
                Pageable pageable = PageRequest.of(0, 10);

                when(estimateRepository.findByCustomerId(CUSTOMER_A, pageable))
                                .thenReturn(new PageImpl<>(List.of(estimate)));
                when(estimateItemRepository.findByEstimate_IdAndDeletedFalse(any()))
                                .thenReturn(List.of());

                Page<EstimateSummaryResponse> result = estimateService.searchEstimates(CUSTOMER_A, null, pageable);

                assertThat(result).isNotNull();
                assertThat(result.getContent()).hasSize(1);
                assertThat(result.getContent().get(0).getCustomerId()).isEqualTo(CUSTOMER_A);
        }

        // ------------------------------------------------------------------
        // AC2 – GET /v1/workexec/estimates/search?vehicleId={id}
        // returns paginated estimates for that vehicle
        // ------------------------------------------------------------------

        /**
         * When the repository returns a single estimate for the given vehicle, the
         * service result contains exactly that estimate with the correct vehicle ID.
         */
        @Test
        void searchEstimates_byVehicleId_returnsEstimatesForThatVehicle() {
                UUID estimateId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                Estimate estimate = buildEstimate(estimateId, CUSTOMER_A, VEHICLE_B, EstimateStatus.PENDING_CUSTOMER);
                Pageable pageable = PageRequest.of(0, 10);

                when(estimateRepository.findByVehicleId(VEHICLE_B, pageable))
                                .thenReturn(new PageImpl<>(List.of(estimate)));
                when(estimateItemRepository.findByEstimate_IdAndDeletedFalse(any()))
                                .thenReturn(List.of());

                Page<EstimateSummaryResponse> result = estimateService.searchEstimates(null, VEHICLE_B, pageable);

                assertThat(result).isNotNull();
                assertThat(result.getContent()).hasSize(1);
                assertThat(result.getContent().get(0).getVehicleId()).isEqualTo(VEHICLE_B);
        }

        // ------------------------------------------------------------------
        // AC3 – GET /v1/workexec/estimates/search?customerId={id}&vehicleId={id}
        // returns estimates matching both filters
        // ------------------------------------------------------------------

        /**
         * When both filters are supplied, the result is scoped to the intersection of
         * customer and vehicle, and is non-empty when the repository returns a match.
         */
        @Test
        void searchEstimates_byBothCustomerAndVehicle_returnsMatchingEstimates() {
                UUID estimateId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                Estimate estimate = buildEstimate(estimateId, CUSTOMER_A, VEHICLE_B, EstimateStatus.APPROVED);
                Pageable pageable = PageRequest.of(0, 10);

                when(estimateRepository.findByCustomerIdAndVehicleId(CUSTOMER_A, VEHICLE_B, pageable))
                                .thenReturn(new PageImpl<>(List.of(estimate)));
                when(estimateItemRepository.findByEstimate_IdAndDeletedFalse(any()))
                                .thenReturn(List.of());

                Page<EstimateSummaryResponse> result = estimateService.searchEstimates(CUSTOMER_A, VEHICLE_B, pageable);

                assertThat(result).isNotNull();
                assertThat(result.getContent()).isNotEmpty();
        }

        // ------------------------------------------------------------------
        // AC4 – Result includes required summary fields
        // ------------------------------------------------------------------

        /**
         * Each summary response in the result page must contain non-null values for
         * {@code id}, {@code estimateNumber}, {@code status}, and {@code createdAt}.
         */
        @Test
        void searchEstimates_resultContainsRequiredFields() {
                UUID estimateId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                Estimate estimate = buildEstimate(estimateId, CUSTOMER_A, VEHICLE_B, EstimateStatus.DRAFT);
                Pageable pageable = PageRequest.of(0, 10);

                when(estimateRepository.findByCustomerId(CUSTOMER_A, pageable))
                                .thenReturn(new PageImpl<>(List.of(estimate)));
                when(estimateItemRepository.findByEstimate_IdAndDeletedFalse(any()))
                                .thenReturn(List.of());

                Page<EstimateSummaryResponse> result = estimateService.searchEstimates(CUSTOMER_A, null, pageable);

                assertThat(result.getContent()).hasSize(1);
                EstimateSummaryResponse summary = result.getContent().get(0);
                assertThat(summary.getId()).isNotNull();
                assertThat(summary.getEstimateNumber()).isNotNull();
                assertThat(summary.getStatus()).isNotNull();
                assertThat(summary.getCreatedAt()).isNotNull();
                // AC4: additional required fields
                assertThat(summary.getCustomerId()).isEqualTo(CUSTOMER_A);
                assertThat(summary.getVehicleId()).isEqualTo(VEHICLE_B);
                assertThat(summary.getLocationId()).isEqualTo(LOCATION_C);
                assertThat(summary.getExpiresAt()).isNotNull();
                assertThat(summary.getSubtotal()).isEqualByComparingTo(BigDecimal.valueOf(100));
                assertThat(summary.getTotal()).isEqualByComparingTo(BigDecimal.valueOf(108));
        }

        // ------------------------------------------------------------------
        // AC5 – CANCELLED and ARCHIVED estimates are still returned
        // ------------------------------------------------------------------

        /**
         * Estimates with status {@code CANCELLED} must appear in search results
         * without any status filtering applied by the service layer.
         */
        @Test
        void searchEstimates_cancelledStatus_isIncluded() {
                UUID estimateId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                Estimate cancelled = buildEstimate(estimateId, CUSTOMER_A, VEHICLE_B, EstimateStatus.CANCELLED);
                Pageable pageable = PageRequest.of(0, 10);

                when(estimateRepository.findByCustomerId(CUSTOMER_A, pageable))
                                .thenReturn(new PageImpl<>(List.of(cancelled)));
                when(estimateItemRepository.findByEstimate_IdAndDeletedFalse(any()))
                                .thenReturn(List.of());

                Page<EstimateSummaryResponse> result = estimateService.searchEstimates(CUSTOMER_A, null, pageable);

                assertThat(result.getContent()).hasSize(1);
                assertThat(result.getContent().get(0).getStatus()).isEqualTo(EstimateStatus.CANCELLED);
        }

        // ------------------------------------------------------------------
        // AC6 – No filters provided → returns all estimates (paginated)
        // ------------------------------------------------------------------

        /**
         * When both {@code customerId} and {@code vehicleId} are {@code null}, the
         * service delegates to an unfiltered paginated repository call and returns all
         * estimates.
         */
        @Test
        void searchEstimates_noFilters_returnsAllEstimates() {
                List<Estimate> all = List.of(
                                buildEstimate(UUID.fromString("00000000-0000-0000-0000-000000000001"), CUSTOMER_A,
                                                VEHICLE_B, EstimateStatus.OPEN),
                                buildEstimate(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                                EstimateStatus.DRAFT));
                Pageable pageable = PageRequest.of(0, 10);

                when(estimateRepository.findAll(pageable))
                                .thenReturn(new PageImpl<>(all, pageable, all.size()));
                when(estimateItemRepository.findByEstimate_IdAndDeletedFalse(any()))
                                .thenReturn(List.of());

                Page<EstimateSummaryResponse> result = estimateService.searchEstimates(null, null, pageable);

                assertThat(result).isNotNull();
                assertThat(result.getTotalElements()).isEqualTo(2L);
        }

        // ------------------------------------------------------------------
        // AC7 – Pagination is respected (page size honoured)
        // ------------------------------------------------------------------

        /**
         * When the repository returns a page scoped to the requested page size, the
         * service result mirrors those bounds and exposes the correct total element
         * count from the underlying store.
         */
        @Test
        void searchEstimates_paginationRespected_pageSizeApplied() {
                List<Estimate> fiveEstimates = List.of(
                                buildEstimate(UUID.fromString("00000000-0000-0000-0000-000000000001"), CUSTOMER_A,
                                                VEHICLE_B, EstimateStatus.OPEN),
                                buildEstimate(UUID.fromString("00000000-0000-0000-0000-000000000001"), CUSTOMER_A,
                                                VEHICLE_B, EstimateStatus.DRAFT),
                                buildEstimate(UUID.fromString("00000000-0000-0000-0000-000000000001"), CUSTOMER_A,
                                                VEHICLE_B, EstimateStatus.APPROVED),
                                buildEstimate(UUID.fromString("00000000-0000-0000-0000-000000000001"), CUSTOMER_A,
                                                VEHICLE_B,
                                                EstimateStatus.PENDING_CUSTOMER),
                                buildEstimate(UUID.fromString("00000000-0000-0000-0000-000000000001"), CUSTOMER_A,
                                                VEHICLE_B, EstimateStatus.INVOICED));
                Pageable pageable = PageRequest.of(0, 5);

                when(estimateRepository.findByCustomerId(CUSTOMER_A, pageable))
                                .thenReturn(new PageImpl<>(fiveEstimates, pageable, 12L));
                when(estimateItemRepository.findByEstimate_IdAndDeletedFalse(any()))
                                .thenReturn(List.of());

                Page<EstimateSummaryResponse> result = estimateService.searchEstimates(CUSTOMER_A, null, pageable);

                assertThat(result).isNotNull();
                assertThat(result.getContent().size()).isLessThanOrEqualTo(5);
                assertThat(result.getTotalElements()).isEqualTo(12L);
        }

        // ------------------------------------------------------------------
        // AC8 – No matching estimates → empty page returned
        // ------------------------------------------------------------------

        /**
         * When the repository returns an empty page (no estimates match the filter),
         * the service result is also empty and non-null.
         */
        @Test
        void searchEstimates_noMatchingEstimates_returnsEmptyPage() {
                UUID unknownCustomer = UUID.fromString("00000000-0000-0000-0000-000000000001");
                Pageable pageable = PageRequest.of(0, 10);

                when(estimateRepository.findByCustomerId(unknownCustomer, pageable))
                                .thenReturn(new PageImpl<>(List.of()));
                when(estimateItemRepository.findByEstimate_IdAndDeletedFalse(any()))
                                .thenReturn(List.of());

                Page<EstimateSummaryResponse> result = estimateService.searchEstimates(unknownCustomer, null, pageable);

                assertThat(result).isNotNull();
                assertThat(result.isEmpty()).isTrue();
        }
}
