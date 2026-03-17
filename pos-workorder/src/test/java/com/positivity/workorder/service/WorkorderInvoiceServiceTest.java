package com.positivity.workorder.service;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import com.positivity.shared.dto.InvoiceLineItem;
import com.positivity.workorder.internal.client.InvoiceClient;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderService;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.internal.service.IdempotencyServiceImpl;
import com.positivity.workorder.internal.service.WorkorderInvoiceServiceImpl;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkorderInvoiceService Unit Tests")
class WorkorderInvoiceServiceTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
        private static final String IDEMPOTENCY_OPERATION_WORKORDER_INVOICE_GENERATE = "workorder.invoice.generate";

        @Spy
        Clock clock = TEST_CLOCK;

        private static final String INV_KEY_1 = "inv-key-1";

        @Mock
        private WorkorderRepository workorderRepository;

        @Mock
        private WorkorderServiceRepository workorderServiceRepository;

        @Mock
        private WorkorderPartRepository workorderPartRepository;

        @Mock
        private IdempotencyServiceImpl idempotencyService;

        @Mock
        private InvoiceClient invoiceClient;

        @InjectMocks
        private WorkorderInvoiceServiceImpl workorderInvoiceService;

        private UUID workorderId;
        private UUID estimateId;
        private UUID approvalId;

        @BeforeEach
        void setUp() {
                workorderId = UUID.fromString("11000000-0000-0000-0000-000000000001");
                estimateId = UUID.fromString("00000000-0000-0000-0000-000000000009");
                approvalId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        }

        @Test
        @DisplayName("generateInvoice creates invoice from completed workorder and persists invoice link")
        @SuppressWarnings("java:S100")
        void generateInvoice_FromCompletedWorkorder_CreatesInvoiceAndPersistsLink() {
                Workorder workorder = completedWorkorder();
                when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));
                when(workorderServiceRepository.findByWorkOrder_Id(workorderId)).thenReturn(List.of(serviceLine()));
                when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId))
                                .thenReturn(List.of(partLine()));
                when(workorderPartRepository.findByWorkorderIdAndWorkOrderServiceIsNull(workorderId))
                                .thenReturn(List.of());
                when(idempotencyService.getExistingInvoiceId(
                                IDEMPOTENCY_OPERATION_WORKORDER_INVOICE_GENERATE,
                                INV_KEY_1)).thenReturn(Optional.empty());

                UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                InvoiceGenerationResponse generated = InvoiceGenerationResponse.builder()
                                .invoiceId(invoiceId)
                                .status("DRAFT")
                                .workorderId(workorderId)
                                .estimateId(estimateId)
                                .approvalId(approvalId)
                                .subtotal(new BigDecimal("170.0000"))
                                .taxAmount(new BigDecimal("10.0000"))
                                .totalAmount(new BigDecimal("180.0000"))
                                .createdAt(Instant.now(TEST_CLOCK))
                                .build();

                when(invoiceClient.createInvoice(any(InvoiceCreationRequest.class))).thenReturn(generated);
                when(workorderRepository.save(any(Workorder.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                InvoiceGenerationResponse response = workorderInvoiceService.generateInvoice(workorderId, INV_KEY_1);

                assertThat(response.getInvoiceId()).isEqualTo(invoiceId);
                assertThat(workorder.getInvoiceId()).isEqualTo(invoiceId);
                verify(workorderRepository).save(workorder);
                verify(idempotencyService).registerInvoiceKey(
                                IDEMPOTENCY_OPERATION_WORKORDER_INVOICE_GENERATE,
                                INV_KEY_1,
                                invoiceId);
        }

        @Test
        @DisplayName("generateInvoice includes traceability links and backfills response links when invoice service omits them")
        void generateInvoice_TraceabilityLinks_ArePropagated() {
                Workorder workorder = completedWorkorder();
                when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));
                when(workorderServiceRepository.findByWorkOrder_Id(workorderId)).thenReturn(List.of(serviceLine()));
                when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId)).thenReturn(List.of());
                when(workorderPartRepository.findByWorkorderIdAndWorkOrderServiceIsNull(workorderId))
                                .thenReturn(List.of());

                UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                InvoiceGenerationResponse upstreamResponse = InvoiceGenerationResponse.builder()
                                .invoiceId(invoiceId)
                                .status("DRAFT")
                                .subtotal(new BigDecimal("120.0000"))
                                .taxAmount(BigDecimal.ZERO)
                                .totalAmount(new BigDecimal("120.0000"))
                                .build();
                when(invoiceClient.createInvoice(any(InvoiceCreationRequest.class))).thenReturn(upstreamResponse);
                when(workorderRepository.save(any(Workorder.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                InvoiceGenerationResponse response = workorderInvoiceService.generateInvoice(workorderId, null);

                ArgumentCaptor<InvoiceCreationRequest> requestCaptor = ArgumentCaptor
                                .forClass(InvoiceCreationRequest.class);
                verify(invoiceClient).createInvoice(requestCaptor.capture());

                InvoiceCreationRequest sentRequest = requestCaptor.getValue();
                assertThat(sentRequest.getWorkorderId()).isEqualTo(workorderId);
                assertThat(sentRequest.getEstimateId()).isEqualTo(estimateId);
                assertThat(sentRequest.getApprovalId()).isEqualTo(approvalId);

                assertThat(response.getWorkorderId()).isEqualTo(workorderId);
                assertThat(response.getEstimateId()).isEqualTo(estimateId);
                assertThat(response.getApprovalId()).isEqualTo(approvalId);
        }

        @Test
        @DisplayName("generateInvoice converts workorder service and part items to invoice line items")
        void generateInvoice_ConvertsWorkorderItemsToInvoiceLineItems() {
                Workorder workorder = completedWorkorder();
                WorkorderService labor = WorkorderService.builder()
                                .description("Labor - Diagnostics")
                                .quantity(new BigDecimal("2.0000"))
                                .unitPrice(new BigDecimal("75.0000"))
                                .lineTotal(null)
                                .build();
                WorkorderPart part = WorkorderPart.builder()
                                .description("Brake Pad")
                                .quantity(new BigDecimal("1.0000"))
                                .unitPrice(new BigDecimal("60.0000"))
                                .lineTotal(new BigDecimal("60.0000"))
                                .build();

                when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));
                when(workorderServiceRepository.findByWorkOrder_Id(workorderId)).thenReturn(List.of(labor));
                when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId))
                                .thenReturn(List.of(part));
                when(workorderPartRepository.findByWorkorderIdAndWorkOrderServiceIsNull(workorderId))
                                .thenReturn(List.of());
                when(invoiceClient.createInvoice(any(InvoiceCreationRequest.class)))
                                .thenReturn(InvoiceGenerationResponse.builder()
                                                .invoiceId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                                .status("DRAFT")
                                                .subtotal(new BigDecimal("210.0000"))
                                                .taxAmount(BigDecimal.ZERO)
                                                .totalAmount(new BigDecimal("210.0000"))
                                                .build());
                when(workorderRepository.save(any(Workorder.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                workorderInvoiceService.generateInvoice(workorderId, null);

                ArgumentCaptor<InvoiceCreationRequest> requestCaptor = ArgumentCaptor
                                .forClass(InvoiceCreationRequest.class);
                verify(invoiceClient).createInvoice(requestCaptor.capture());

                List<InvoiceLineItem> lineItems = requestCaptor.getValue().getLineItems();
                assertThat(lineItems).hasSize(2);

                InvoiceLineItem laborLine = lineItems.get(0);
                assertThat(laborLine.getDescription()).isEqualTo("Labor - Diagnostics");
                assertThat(laborLine.getQuantity()).isEqualByComparingTo("2.0000");
                assertThat(laborLine.getUnitPrice()).isEqualByComparingTo("75.0000");
                assertThat(laborLine.getAmount()).isEqualByComparingTo("150.0000");

                InvoiceLineItem partLine = lineItems.get(1);
                assertThat(partLine.getDescription()).isEqualTo("Brake Pad");
                assertThat(partLine.getQuantity()).isEqualByComparingTo("1.0000");
                assertThat(partLine.getUnitPrice()).isEqualByComparingTo("60.0000");
                assertThat(partLine.getAmount()).isEqualByComparingTo("60.0000");
        }

        @Test
        @DisplayName("generateInvoice returns existing invoice details when workorder already has invoice")
        void generateInvoice_AlreadyGenerated_ReturnsExistingInvoice() {
                UUID existingInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                Workorder workorder = completedWorkorder();
                workorder.setInvoiceId(existingInvoiceId);

                when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));

                InvoiceGenerationResponse existingInvoiceDetails = InvoiceGenerationResponse.builder()
                                .invoiceId(existingInvoiceId)
                                .status("DRAFT")
                                .workorderId(workorderId)
                                .estimateId(estimateId)
                                .approvalId(approvalId)
                                .subtotal(new BigDecimal("120.00"))
                                .taxAmount(new BigDecimal("10.00"))
                                .totalAmount(new BigDecimal("130.00"))
                                .createdAt(Instant.now(TEST_CLOCK))
                                .build();

                when(invoiceClient.getInvoice(existingInvoiceId)).thenReturn(existingInvoiceDetails);

                InvoiceGenerationResponse response = workorderInvoiceService.generateInvoice(workorderId, null);

                assertThat(response.getInvoiceId()).isEqualTo(existingInvoiceId);
                assertThat(response.getStatus()).isEqualTo("DRAFT");
                assertThat(response.getWorkorderId()).isEqualTo(workorderId);
                assertThat(response.getEstimateId()).isEqualTo(estimateId);
                assertThat(response.getApprovalId()).isEqualTo(approvalId);
                assertThat(response.getSubtotal()).isEqualByComparingTo("120.00");
                assertThat(response.getTaxAmount()).isEqualByComparingTo("10.00");
                assertThat(response.getTotalAmount()).isEqualByComparingTo("130.00");
                verify(invoiceClient, never()).createInvoice(any());
                verify(invoiceClient).getInvoice(existingInvoiceId);
        }

        @Test
        @DisplayName("generateInvoice deduplicates parts when same part has both workorder and workOrderService references")
        void generateInvoice_DeduplicatesParts_WhenPartHasBothReferences() {
                Workorder workorder = completedWorkorder();

                // Create a duplicate part that will be returned by both queries (same ID)
                // This simulates a scenario where a part has both workorder and
                // workOrderService references
                UUID duplicatePartId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                WorkorderPart duplicatePart1 = WorkorderPart.builder()
                                .id(duplicatePartId)
                                .description("Oil Filter")
                                .quantity(new BigDecimal("1.0000"))
                                .unitPrice(new BigDecimal("15.0000"))
                                .lineTotal(new BigDecimal("15.0000"))
                                .build();

                WorkorderPart duplicatePart2 = WorkorderPart.builder()
                                .id(duplicatePartId) // Same ID as duplicatePart1
                                .description("Oil Filter")
                                .quantity(new BigDecimal("1.0000"))
                                .unitPrice(new BigDecimal("15.0000"))
                                .lineTotal(new BigDecimal("15.0000"))
                                .build();

                // Create a standalone part with unique ID
                WorkorderPart standalonePart = WorkorderPart.builder()
                                .id(UUID.fromString("00000000-0000-0000-0000-000000000033"))
                                .description("Standalone Part")
                                .quantity(new BigDecimal("1.0000"))
                                .unitPrice(new BigDecimal("25.0000"))
                                .lineTotal(new BigDecimal("25.0000"))
                                .build();

                when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));
                when(workorderServiceRepository.findByWorkOrder_Id(workorderId)).thenReturn(List.of());

                // Simulate a scenario where the same part (by ID) is returned by both queries
                // This tests the service-level deduplication safety measure
                when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId))
                                .thenReturn(List.of(duplicatePart1));
                when(workorderPartRepository.findByWorkorderIdAndWorkOrderServiceIsNull(workorderId))
                                .thenReturn(List.of(duplicatePart2, standalonePart));

                when(invoiceClient.createInvoice(any(InvoiceCreationRequest.class)))
                                .thenReturn(InvoiceGenerationResponse.builder()
                                                .invoiceId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                                .status("DRAFT")
                                                .subtotal(new BigDecimal("40.0000"))
                                                .taxAmount(BigDecimal.ZERO)
                                                .totalAmount(new BigDecimal("40.0000"))
                                                .build());
                when(workorderRepository.save(any(Workorder.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                workorderInvoiceService.generateInvoice(workorderId, null);

                ArgumentCaptor<InvoiceCreationRequest> requestCaptor = ArgumentCaptor
                                .forClass(InvoiceCreationRequest.class);
                verify(invoiceClient).createInvoice(requestCaptor.capture());

                List<InvoiceLineItem> lineItems = requestCaptor.getValue().getLineItems();

                // Verify we have exactly 2 distinct parts (duplicate was filtered out)
                assertThat(lineItems).hasSize(2);

                // Verify both parts are present (Oil Filter should appear once, not twice)
                assertThat(lineItems)
                                .extracting(InvoiceLineItem::getDescription)
                                .containsExactlyInAnyOrder("Oil Filter", "Standalone Part");

                // Verify amounts are correct (no double-billing - Oil Filter $15 + Standalone
                // Part $25 = $40)
                BigDecimal totalAmount = lineItems.stream()
                                .map(InvoiceLineItem::getAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(totalAmount).isEqualByComparingTo("40.0000");
        }

        @Test
        @DisplayName("generateInvoice handles race condition when idempotency key already registered")
        void generateInvoice_RaceCondition_ReturnsExistingInvoice() {
                Workorder workorder = completedWorkorder();
                when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));
                when(workorderServiceRepository.findByWorkOrder_Id(workorderId)).thenReturn(List.of(serviceLine()));
                when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId))
                                .thenReturn(List.of(partLine()));
                when(workorderPartRepository.findByWorkorderIdAndWorkOrderServiceIsNull(workorderId))
                                .thenReturn(List.of());

                UUID newInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID existingInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                InvoiceGenerationResponse generated = InvoiceGenerationResponse.builder()
                                .invoiceId(newInvoiceId)
                                .status("DRAFT")
                                .workorderId(workorderId)
                                .estimateId(estimateId)
                                .approvalId(approvalId)
                                .subtotal(new BigDecimal("170.0000"))
                                .taxAmount(new BigDecimal("10.0000"))
                                .totalAmount(new BigDecimal("180.0000"))
                                .createdAt(Instant.now(TEST_CLOCK))
                                .build();

                when(invoiceClient.createInvoice(any(InvoiceCreationRequest.class))).thenReturn(generated);

                // First call to getExistingInvoiceId (early check) returns empty, second call
                // (after collision) returns existing
                when(idempotencyService.getExistingInvoiceId(
                                IDEMPOTENCY_OPERATION_WORKORDER_INVOICE_GENERATE,
                                "inv-key-race"))
                                .thenReturn(Optional.empty())
                                .thenReturn(Optional.of(existingInvoiceId));

                // Simulate race condition: registerInvoiceKey throws
                // DataIntegrityViolationException
                doThrow(new org.springframework.dao.DataIntegrityViolationException("Duplicate key"))
                                .when(idempotencyService)
                                .registerInvoiceKey(
                                                IDEMPOTENCY_OPERATION_WORKORDER_INVOICE_GENERATE,
                                                "inv-key-race",
                                                newInvoiceId);

                // Mock the existing invoice that will be fetched when race condition is
                // detected
                InvoiceGenerationResponse existingInvoice = InvoiceGenerationResponse.builder()
                                .invoiceId(existingInvoiceId)
                                .status("DRAFT")
                                .workorderId(workorderId)
                                .estimateId(estimateId)
                                .approvalId(approvalId)
                                .subtotal(new BigDecimal("170.0000"))
                                .taxAmount(new BigDecimal("10.0000"))
                                .totalAmount(new BigDecimal("180.0000"))
                                .createdAt(Instant.now(TEST_CLOCK))
                                .build();
                when(invoiceClient.getInvoice(existingInvoiceId)).thenReturn(existingInvoice);

                InvoiceGenerationResponse response = workorderInvoiceService.generateInvoice(workorderId,
                                "inv-key-race");

                // Should return the existing invoice, not the newly created one
                assertThat(response.getInvoiceId()).isEqualTo(existingInvoiceId);
                assertThat(response.getStatus()).isEqualTo("DRAFT");

                // Verify workorder invoiceId was NOT set to the new invoice (no save should
                // happen in race condition)
                verify(workorderRepository, never()).save(any(Workorder.class));
                assertThat(workorder.getInvoiceId()).isNull(); // workorder should remain unchanged
        }

        @Test
        @DisplayName("generateInvoice excludes CANCELLED services and parts from invoice line items")
        void generateInvoice_ExcludesCancelledItems() {
                Workorder workorder = completedWorkorder();

                // Create billable items
                WorkorderService completedService = WorkorderService.builder()
                                .description("Completed Service")
                                .quantity(new BigDecimal("1.0000"))
                                .unitPrice(new BigDecimal("100.0000"))
                                .lineTotal(new BigDecimal("100.0000"))
                                .status(WorkorderItemStatus.COMPLETED)
                                .build();

                WorkorderPart completedPart = WorkorderPart.builder()
                                .description("Completed Part")
                                .quantity(new BigDecimal("1.0000"))
                                .unitPrice(new BigDecimal("50.0000"))
                                .lineTotal(new BigDecimal("50.0000"))
                                .status(WorkorderItemStatus.COMPLETED)
                                .build();

                // Create non-billable (CANCELLED) items
                WorkorderService cancelledService = WorkorderService.builder()
                                .description("Cancelled Service")
                                .quantity(new BigDecimal("1.0000"))
                                .unitPrice(new BigDecimal("200.0000"))
                                .lineTotal(new BigDecimal("200.0000"))
                                .status(WorkorderItemStatus.CANCELLED)
                                .build();

                WorkorderPart cancelledPart = WorkorderPart.builder()
                                .description("Cancelled Part")
                                .quantity(new BigDecimal("2.0000"))
                                .unitPrice(new BigDecimal("75.0000"))
                                .lineTotal(new BigDecimal("150.0000"))
                                .status(WorkorderItemStatus.CANCELLED)
                                .build();

                when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));
                when(workorderServiceRepository.findByWorkOrder_Id(workorderId))
                                .thenReturn(List.of(completedService, cancelledService));
                when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId))
                                .thenReturn(List.of(completedPart, cancelledPart));
                when(workorderPartRepository.findByWorkorderIdAndWorkOrderServiceIsNull(workorderId))
                                .thenReturn(List.of());
                when(invoiceClient.createInvoice(any(InvoiceCreationRequest.class)))
                                .thenReturn(InvoiceGenerationResponse.builder()
                                                .invoiceId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                                .status("DRAFT")
                                                .subtotal(new BigDecimal("150.0000"))
                                                .taxAmount(BigDecimal.ZERO)
                                                .totalAmount(new BigDecimal("150.0000"))
                                                .build());
                when(workorderRepository.save(any(Workorder.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                workorderInvoiceService.generateInvoice(workorderId, null);

                ArgumentCaptor<InvoiceCreationRequest> requestCaptor = ArgumentCaptor
                                .forClass(InvoiceCreationRequest.class);
                verify(invoiceClient).createInvoice(requestCaptor.capture());

                List<InvoiceLineItem> lineItems = requestCaptor.getValue().getLineItems();

                // Verify only billable items are included (CANCELLED items excluded)
                assertThat(lineItems).hasSize(2);
                List<String> descriptions = lineItems.stream()
                                .map(InvoiceLineItem::getDescription)
                                .toList();
                // Verify only completed items are present (CANCELLED items should not appear)
                assertThat(descriptions).containsExactlyInAnyOrder("Completed Service", "Completed Part")
                                .doesNotContain("Cancelled Service", "Cancelled Part");

                // Verify amounts match only billable items (Completed Service $100 + Completed
                // Part $50 = $150)
                BigDecimal totalAmount = lineItems.stream()
                                .map(InvoiceLineItem::getAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(totalAmount).isEqualByComparingTo("150.0000");
        }

        @Test
        @DisplayName("generateInvoice with idempotency key replay fetches existing invoice and updates workorder")
        void generateInvoice_IdempotencyKeyReplay_FetchesExistingInvoiceAndUpdatesWorkorder() {
                UUID existingInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                Workorder workorder = completedWorkorder();
                // Workorder does not have invoice ID yet (simulating a retry scenario)
                workorder.setInvoiceId(null);

                when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));
                when(idempotencyService.getExistingInvoiceId(
                                IDEMPOTENCY_OPERATION_WORKORDER_INVOICE_GENERATE,
                                "retry-key-123"))
                                .thenReturn(Optional.of(existingInvoiceId));

                InvoiceGenerationResponse existingInvoiceDetails = InvoiceGenerationResponse.builder()
                                .invoiceId(existingInvoiceId)
                                .status("DRAFT")
                                .workorderId(workorderId)
                                .estimateId(estimateId)
                                .approvalId(approvalId)
                                .subtotal(new BigDecimal("150.00"))
                                .taxAmount(new BigDecimal("12.00"))
                                .totalAmount(new BigDecimal("162.00"))
                                .createdAt(Instant.now(TEST_CLOCK))
                                .build();

                when(invoiceClient.getInvoice(existingInvoiceId)).thenReturn(existingInvoiceDetails);
                when(workorderRepository.save(any(Workorder.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                InvoiceGenerationResponse response = workorderInvoiceService.generateInvoice(workorderId,
                                "retry-key-123");

                assertThat(response.getInvoiceId()).isEqualTo(existingInvoiceId);
                assertThat(response.getStatus()).isEqualTo("DRAFT");
                assertThat(response.getSubtotal()).isEqualByComparingTo("150.00");
                assertThat(response.getTaxAmount()).isEqualByComparingTo("12.00");
                assertThat(response.getTotalAmount()).isEqualByComparingTo("162.00");

                // Verify workorder was updated with invoice ID
                assertThat(workorder.getInvoiceId()).isEqualTo(existingInvoiceId);
                verify(workorderRepository).save(workorder);

                // Verify invoice was fetched from service (not created)
                verify(invoiceClient).getInvoice(existingInvoiceId);
                verify(invoiceClient, never()).createInvoice(any());
        }

        private Workorder completedWorkorder() {
                return Workorder.builder()
                                .id(workorderId)
                                .estimate(new Estimate(estimateId))
                                .approvalId(approvalId)
                                .status(WorkorderStatus.COMPLETED)
                                .updatedAt(LocalDateTime.now(TEST_CLOCK))
                                .completedAt(Instant.now(TEST_CLOCK))
                                .build();
        }

        private WorkorderService serviceLine() {
                return WorkorderService.builder()
                                .description("Labor - Inspection")
                                .quantity(new BigDecimal("1.0000"))
                                .unitPrice(new BigDecimal("120.0000"))
                                .lineTotal(new BigDecimal("120.0000"))
                                .build();
        }

        private WorkorderPart partLine() {
                return WorkorderPart.builder()
                                .description("Air Filter")
                                .quantity(new BigDecimal("1.0000"))
                                .unitPrice(new BigDecimal("50.0000"))
                                .lineTotal(new BigDecimal("50.0000"))
                                .build();
        }
}
