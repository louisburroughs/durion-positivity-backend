package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.dto.EstimateSummaryResponse;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.service.CustomerReferenceService.CustomerContact;
import com.positivity.workorder.internal.service.CustomerReferenceService.CustomerRef;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

/**
 * Unit tests for {@link EstimateService#findEstimatesByQuery(String, Pageable)}.
 *
 * <p>Covers the free-text {@code q} search that matches the estimate number,
 * customer name, or estimate id, and enriches each summary row with the resolved
 * customer display name.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EstimateSearchByQueryTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private EstimateRepository estimateRepository;

    @Mock
    private EstimateItemRepository estimateItemRepository;

    @Mock
    private CustomerReferenceService customerReferenceService;

    @InjectMocks
    private EstimateServiceImpl estimateService;

    private static final UUID CUSTOMER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID VEHICLE_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID LOCATION_C = UUID.fromString("cccccccc-0000-0000-0000-000000000001");

    private Estimate buildEstimate(UUID id, UUID customerId, String estimateNumber) {
        return Estimate.builder()
                .id(id)
                .estimateNumber(estimateNumber)
                .customerId(customerId)
                .vehicleId(VEHICLE_B)
                .locationId(LOCATION_C)
                .status(EstimateStatus.DRAFT)
                .subtotal(BigDecimal.valueOf(100))
                .taxAmount(BigDecimal.valueOf(8))
                .total(BigDecimal.valueOf(108))
                .currencyUomId("USD")
                .createdAt(Instant.now(TEST_CLOCK))
                .build();
    }

    @Test
    void findEstimatesByQuery_nameOrNumberMatch_enrichesCustomerNameAndReturnsEstimate() {
        UUID estimateId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        Estimate estimate = buildEstimate(estimateId, CUSTOMER_A, "EST-2024-1001");
        Pageable pageable = PageRequest.of(0, 10);

        when(customerReferenceService.searchIdsByName(eq("Acme"), anyInt()))
                .thenReturn(List.of(new CustomerRef(CUSTOMER_A, "Acme Corp")));
        when(estimateRepository.searchByQuery(eq("Acme"), anyList(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(estimate)));
        when(customerReferenceService.resolveAll(anyList()))
                .thenReturn(Map.of(CUSTOMER_A, new CustomerContact("Acme Corp", null)));

        Page<EstimateSummaryResponse> result = estimateService.findEstimatesByQuery("Acme", pageable);

        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        EstimateSummaryResponse summary = result.getContent().get(0);
        assertThat(summary.getId()).isEqualTo(estimateId);
        assertThat(summary.getEstimateNumber()).isEqualTo("EST-2024-1001");
        assertThat(summary.getCustomerName()).isEqualTo("Acme Corp");
    }

    @Test
    void findEstimatesByQuery_uuidQuery_passesParsedUuidAsIdQuery() {
        UUID estimateId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        String q = estimateId.toString();
        Estimate estimate = buildEstimate(estimateId, CUSTOMER_A, "EST-2024-2002");
        Pageable pageable = PageRequest.of(0, 10);

        when(customerReferenceService.searchIdsByName(eq(q), anyInt())).thenReturn(List.of());
        when(estimateRepository.searchByQuery(eq(q), anyList(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(estimate)));
        when(customerReferenceService.resolveAll(anyList()))
                .thenReturn(Map.of(CUSTOMER_A, new CustomerContact("customer-" + CUSTOMER_A, null)));

        estimateService.findEstimatesByQuery(q, pageable);

        ArgumentCaptor<UUID> idQueryCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(estimateRepository).searchByQuery(eq(q), anyList(), idQueryCaptor.capture(), eq(pageable));
        assertThat(idQueryCaptor.getValue()).isEqualTo(estimateId);
    }
}
