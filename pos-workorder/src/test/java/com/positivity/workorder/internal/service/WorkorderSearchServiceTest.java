package com.positivity.workorder.internal.service;

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
import java.time.Instant;
import java.time.LocalDate;
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
 * customer-name match (with enrichment), the UUID-query path, the optional
 * exact customer/vehicle filters, and the E12 (#1600) structured filters
 * (status, createdFrom/createdTo, technicianId).
 *
 * <p>{@code createdFrom}/{@code createdTo} are always forwarded to the repository as non-null
 * {@code Instant}s: a {@code null} caller-supplied bound is widened by the service to a sentinel
 * (far past/future) rather than passed through as {@code null}, so those positions use {@code
 * any()} rather than {@code isNull()} in the verifications below.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkorderSearchServiceTest {

    private static final UUID CUSTOMER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID VEHICLE_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID WORKORDER_ID = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID TECHNICIAN_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000001");

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

    private void stubPassthroughEnrichment(UUID customerId, Workorder workorder, Pageable pageable) {
        when(workorderRepository.searchByQuery(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(workorder)));
        when(customerReferenceService.resolveAll(any()))
                .thenReturn(Map.of(customerId, new CustomerReferenceService.CustomerContact("Acme Auto", null)));
        when(vehicleReferenceService.resolveAll(any())).thenReturn(Map.of());
    }

    @Test
    void search_byCustomerName_returnsEnrichedResult() {
        Pageable pageable = PageRequest.of(0, 25);
        Workorder workorder = buildWorkorder(WORKORDER_ID, CUSTOMER_A, WorkorderStatus.APPROVED);

        when(customerReferenceService.searchIdsByName(eq("Acme"), anyInt()))
                .thenReturn(List.of(new CustomerReferenceService.CustomerRef(CUSTOMER_A, "Acme Auto")));
        stubPassthroughEnrichment(CUSTOMER_A, workorder, pageable);

        Page<WorkorderSearchResult> result =
                workorderSearchService.search("Acme", null, null, null, null, null, null, pageable);

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
        stubPassthroughEnrichment(CUSTOMER_A, workorder, pageable);

        workorderSearchService.search(q, null, null, null, null, null, null, pageable);

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
                        isNull(),
                        any(),
                        any(),
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
        stubPassthroughEnrichment(CUSTOMER_A, workorder, pageable);

        Page<WorkorderSearchResult> result =
                workorderSearchService.search("", CUSTOMER_A, VEHICLE_B, null, null, null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        verify(workorderRepository)
                .searchByQuery(
                        eq(""),
                        any(),
                        isNull(),
                        eq(CUSTOMER_A),
                        eq(VEHICLE_B),
                        isNull(),
                        any(),
                        any(),
                        isNull(),
                        eq(pageable));
    }

    @Test
    void search_filtersCombinableWithFreeTextQuery() {
        Pageable pageable = PageRequest.of(0, 25);
        Workorder workorder = buildWorkorder(WORKORDER_ID, CUSTOMER_A, WorkorderStatus.APPROVED);

        when(customerReferenceService.searchIdsByName(eq("Acme"), anyInt()))
                .thenReturn(List.of(new CustomerReferenceService.CustomerRef(CUSTOMER_A, "Acme Auto")));
        stubPassthroughEnrichment(CUSTOMER_A, workorder, pageable);

        workorderSearchService.search("Acme", null, VEHICLE_B, null, null, null, null, pageable);

        verify(workorderRepository)
                .searchByQuery(
                        eq("Acme"),
                        any(),
                        isNull(),
                        isNull(),
                        eq(VEHICLE_B),
                        isNull(),
                        any(),
                        any(),
                        isNull(),
                        eq(pageable));
    }

    @Test
    void search_withStatusFilter_passesExactStatusToRepository() {
        Pageable pageable = PageRequest.of(0, 25);
        Workorder workorder = buildWorkorder(WORKORDER_ID, CUSTOMER_A, WorkorderStatus.APPROVED);

        when(customerReferenceService.searchIdsByName(anyString(), anyInt())).thenReturn(List.of());
        stubPassthroughEnrichment(CUSTOMER_A, workorder, pageable);

        // Q5 gate scenario (issue #1600 / analytics-capability-plan.md E12): "open work orders for
        // customers more than 60 days past due" — one open status combined with customerId in a
        // single server-side call.
        workorderSearchService.search("", CUSTOMER_A, null, WorkorderStatus.APPROVED, null, null, null, pageable);

        verify(workorderRepository)
                .searchByQuery(
                        eq(""),
                        any(),
                        isNull(),
                        eq(CUSTOMER_A),
                        isNull(),
                        eq(WorkorderStatus.APPROVED),
                        any(),
                        any(),
                        isNull(),
                        eq(pageable));
    }

    @Test
    void search_withoutStatusFilter_passesNullToRepository() {
        Pageable pageable = PageRequest.of(0, 25);
        Workorder workorder = buildWorkorder(WORKORDER_ID, CUSTOMER_A, WorkorderStatus.APPROVED);

        when(customerReferenceService.searchIdsByName(anyString(), anyInt())).thenReturn(List.of());
        stubPassthroughEnrichment(CUSTOMER_A, workorder, pageable);

        workorderSearchService.search("", null, null, null, null, null, null, pageable);

        verify(workorderRepository)
                .searchByQuery(
                        eq(""), any(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull(), eq(pageable));
    }

    @Test
    void search_withCreatedDateWindow_convertsToUtcInstantBounds() {
        Pageable pageable = PageRequest.of(0, 25);
        Workorder workorder = buildWorkorder(WORKORDER_ID, CUSTOMER_A, WorkorderStatus.APPROVED);

        when(customerReferenceService.searchIdsByName(anyString(), anyInt())).thenReturn(List.of());
        stubPassthroughEnrichment(CUSTOMER_A, workorder, pageable);

        workorderSearchService.search(
                "", null, null, null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), null, pageable);

        verify(workorderRepository)
                .searchByQuery(
                        eq(""),
                        any(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(Instant.parse("2026-06-01T00:00:00Z")),
                        eq(Instant.parse("2026-07-01T00:00:00Z")),
                        isNull(),
                        eq(pageable));
    }

    @Test
    void search_withoutCreatedDateWindow_widensToUnboundedSentinels() {
        Pageable pageable = PageRequest.of(0, 25);
        Workorder workorder = buildWorkorder(WORKORDER_ID, CUSTOMER_A, WorkorderStatus.APPROVED);

        when(customerReferenceService.searchIdsByName(anyString(), anyInt())).thenReturn(List.of());
        stubPassthroughEnrichment(CUSTOMER_A, workorder, pageable);

        workorderSearchService.search("", null, null, null, null, null, null, pageable);

        // Never null: a null bound is not a type Postgres can infer inside a temporal comparison, so
        // the service widens to a far-past/far-future sentinel instead (mirrors pos-people's
        // TimeEntryServiceImpl.UNBOUNDED_START/END idiom).
        verify(workorderRepository)
                .searchByQuery(
                        eq(""),
                        any(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(Instant.parse("0001-01-01T00:00:00Z")),
                        eq(Instant.parse("9999-12-31T23:59:59Z")),
                        isNull(),
                        eq(pageable));
    }

    @Test
    void search_withTechnicianIdFilter_passesTechnicianIdToRepository() {
        Pageable pageable = PageRequest.of(0, 25);
        Workorder workorder = buildWorkorder(WORKORDER_ID, CUSTOMER_A, WorkorderStatus.APPROVED);

        when(customerReferenceService.searchIdsByName(anyString(), anyInt())).thenReturn(List.of());
        stubPassthroughEnrichment(CUSTOMER_A, workorder, pageable);

        workorderSearchService.search("", null, null, null, null, null, TECHNICIAN_ID, pageable);

        verify(workorderRepository)
                .searchByQuery(
                        eq(""),
                        any(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        any(),
                        any(),
                        eq(TECHNICIAN_ID),
                        eq(pageable));
    }
}
