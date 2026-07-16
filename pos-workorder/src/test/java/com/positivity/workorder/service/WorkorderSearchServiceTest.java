package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.dto.WorkorderSearchResult;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.service.CustomerReferenceService;
import com.positivity.workorder.internal.service.VehicleReferenceService;
import com.positivity.workorder.internal.service.WorkorderSearchServiceImpl;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for the free-text workorder search service. Covers the
 * customer-name match (with enrichment), the UUID-query path, and the
 * optional exact customer/vehicle filters.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkorderSearchServiceTest {

    private static final UUID CUSTOMER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID VEHICLE_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID WORKORDER_ID = UUID.fromString("11111111-0000-0000-0000-000000000001");

    @Mock
    private WorkorderRepository workorderRepository;

    @Mock
    private CustomerReferenceService customerReferenceService;

    @Mock
    private VehicleReferenceService vehicleReferenceService;

    @InjectMocks
    private WorkorderSearchServiceImpl workorderSearchService;

    private Workorder buildWorkorder(UUID id, UUID customerId, WorkorderStatus status) {
        return Workorder.builder()
                .id(id)
                .customerId(customerId)
                .status(status)
                .createdAt(Instant.parse("2026-01-15T09:30:00Z"))
                .build();
    }

    @Test
    void search_byCustomerName_returnsEnrichedResult() {
        Pageable pageable = PageRequest.of(0, 25);
        Workorder workorder = buildWorkorder(WORKORDER_ID, CUSTOMER_A, WorkorderStatus.APPROVED);

        when(customerReferenceService.searchIdsByName(eq("Acme"), anyInt()))
                .thenReturn(List.of(new CustomerReferenceService.CustomerRef(CUSTOMER_A, "Acme Auto")));
        when(workorderRepository.searchByQuery(any(), any(), any(), any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(workorder)));
        when(customerReferenceService.resolveAll(any()))
                .thenReturn(Map.of(CUSTOMER_A, new CustomerReferenceService.CustomerContact("Acme Auto", null)));
        when(vehicleReferenceService.resolveAll(any())).thenReturn(Map.of());

        Page<WorkorderSearchResult> result = workorderSearchService.search("Acme", null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        WorkorderSearchResult row = result.getContent().get(0);
        assertThat(row.getWorkorderId()).isEqualTo(WORKORDER_ID);
        assertThat(row.getStatus()).isEqualTo(WorkorderStatus.APPROVED);
        assertThat(row.getCustomerName()).isEqualTo("Acme Auto");
    }

    @Test
    void search_byUuid_passesParsedIdQueryToRepository() {
        Pageable pageable = PageRequest.of(0, 25);
        String q = WORKORDER_ID.toString();
        Workorder workorder = buildWorkorder(WORKORDER_ID, CUSTOMER_A, WorkorderStatus.DRAFT);

        when(customerReferenceService.searchIdsByName(anyString(), anyInt())).thenReturn(List.of());
        when(workorderRepository.searchByQuery(any(), any(), any(), any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(workorder)));
        when(customerReferenceService.resolveAll(any()))
                .thenReturn(Map.of(CUSTOMER_A, new CustomerReferenceService.CustomerContact("Acme Auto", null)));
        when(vehicleReferenceService.resolveAll(any())).thenReturn(Map.of());

        workorderSearchService.search(q, null, null, pageable);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> customerIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<UUID> idQueryCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(workorderRepository)
                .searchByQuery(
                        anyString(),
                        customerIdsCaptor.capture(),
                        idQueryCaptor.capture(),
                        isNull(),
                        isNull(),
                        eq(pageable));

        assertThat(idQueryCaptor.getValue()).isEqualTo(WORKORDER_ID);
        // empty name match → sentinel id that cannot match a real workorder
        assertThat(customerIdsCaptor.getValue()).containsExactly(new UUID(0, 0));
    }

    @Test
    void search_withCustomerAndVehicleFilters_passesFiltersToRepository() {
        Pageable pageable = PageRequest.of(0, 25);
        Workorder workorder = buildWorkorder(WORKORDER_ID, CUSTOMER_A, WorkorderStatus.APPROVED);

        when(customerReferenceService.searchIdsByName(anyString(), anyInt())).thenReturn(List.of());
        when(workorderRepository.searchByQuery(any(), any(), any(), any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(workorder)));
        when(customerReferenceService.resolveAll(any()))
                .thenReturn(Map.of(CUSTOMER_A, new CustomerReferenceService.CustomerContact("Acme Auto", null)));
        when(vehicleReferenceService.resolveAll(any())).thenReturn(Map.of());

        Page<WorkorderSearchResult> result = workorderSearchService.search("", CUSTOMER_A, VEHICLE_B, pageable);

        assertThat(result.getContent()).hasSize(1);
        verify(workorderRepository).searchByQuery(eq(""), any(), isNull(), eq(CUSTOMER_A), eq(VEHICLE_B), eq(pageable));
    }

    @Test
    void search_filtersCombinableWithFreeTextQuery() {
        Pageable pageable = PageRequest.of(0, 25);
        Workorder workorder = buildWorkorder(WORKORDER_ID, CUSTOMER_A, WorkorderStatus.APPROVED);

        when(customerReferenceService.searchIdsByName(eq("Acme"), anyInt()))
                .thenReturn(List.of(new CustomerReferenceService.CustomerRef(CUSTOMER_A, "Acme Auto")));
        when(workorderRepository.searchByQuery(any(), any(), any(), any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(workorder)));
        when(customerReferenceService.resolveAll(any()))
                .thenReturn(Map.of(CUSTOMER_A, new CustomerReferenceService.CustomerContact("Acme Auto", null)));
        when(vehicleReferenceService.resolveAll(any())).thenReturn(Map.of());

        workorderSearchService.search("Acme", null, VEHICLE_B, pageable);

        verify(workorderRepository).searchByQuery(eq("Acme"), any(), isNull(), isNull(), eq(VEHICLE_B), eq(pageable));
    }
}
