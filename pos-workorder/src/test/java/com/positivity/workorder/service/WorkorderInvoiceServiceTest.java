package com.positivity.workorder.service;

import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import com.positivity.shared.dto.InvoiceLineItem;
import com.positivity.workorder.internal.client.InvoiceClient;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderService;
import com.positivity.workorder.internal.entity.WorkorderStatus;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkorderInvoiceService Unit Tests")
class WorkorderInvoiceServiceTest {

    @Mock
    private WorkorderRepository workorderRepository;

    @Mock
    private WorkorderServiceRepository workorderServiceRepository;

    @Mock
    private WorkorderPartRepository workorderPartRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private InvoiceClient invoiceClient;

    @InjectMocks
    private WorkorderInvoiceService workorderInvoiceService;

    private UUID workorderId;
    private UUID estimateId;
    private UUID approvalId;

    @BeforeEach
    void setUp() {
        workorderId = UUID.randomUUID();
        estimateId = UUID.randomUUID();
        approvalId = UUID.randomUUID();
    }

    @Test
    @DisplayName("generateInvoice creates invoice from completed workorder and persists invoice link")
    void generateInvoice_FromCompletedWorkorder_CreatesInvoiceAndPersistsLink() {
        Workorder workorder = completedWorkorder();
        when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));
        when(workorderServiceRepository.findByWorkOrder_Id(workorderId)).thenReturn(List.of(serviceLine()));
        when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId)).thenReturn(List.of(partLine()));
        when(workorderPartRepository.findByWorkorderId(workorderId)).thenReturn(List.of());
        when(idempotencyService.getExistingInvoiceId("inv-key-1")).thenReturn(Optional.empty());

        UUID invoiceId = UUID.randomUUID();
        InvoiceGenerationResponse generated = InvoiceGenerationResponse.builder()
                .invoiceId(invoiceId)
                .status("DRAFT")
                .workorderId(workorderId)
                .estimateId(estimateId)
                .approvalId(approvalId)
                .subtotal(new BigDecimal("170.0000"))
                .taxAmount(new BigDecimal("10.0000"))
                .totalAmount(new BigDecimal("180.0000"))
                .createdAt(Instant.now())
                .build();

        when(invoiceClient.createInvoice(any(InvoiceCreationRequest.class))).thenReturn(generated);
        when(workorderRepository.save(any(Workorder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceGenerationResponse response = workorderInvoiceService.generateInvoice(workorderId, "inv-key-1");

        assertThat(response.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(workorder.getInvoiceId()).isEqualTo(invoiceId);
        verify(workorderRepository).save(workorder);
        verify(idempotencyService).registerInvoiceKey("inv-key-1", invoiceId);
    }

    @Test
    @DisplayName("generateInvoice includes traceability links and backfills response links when invoice service omits them")
    void generateInvoice_TraceabilityLinks_ArePropagated() {
        Workorder workorder = completedWorkorder();
        when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));
        when(workorderServiceRepository.findByWorkOrder_Id(workorderId)).thenReturn(List.of(serviceLine()));
        when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId)).thenReturn(List.of());
        when(workorderPartRepository.findByWorkorderId(workorderId)).thenReturn(List.of());

        UUID invoiceId = UUID.randomUUID();
        InvoiceGenerationResponse upstreamResponse = InvoiceGenerationResponse.builder()
                .invoiceId(invoiceId)
                .status("DRAFT")
                .subtotal(new BigDecimal("120.0000"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("120.0000"))
                .build();
        when(invoiceClient.createInvoice(any(InvoiceCreationRequest.class))).thenReturn(upstreamResponse);
        when(workorderRepository.save(any(Workorder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceGenerationResponse response = workorderInvoiceService.generateInvoice(workorderId, null);

        ArgumentCaptor<InvoiceCreationRequest> requestCaptor = ArgumentCaptor.forClass(InvoiceCreationRequest.class);
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
        when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId)).thenReturn(List.of(part));
        when(workorderPartRepository.findByWorkorderId(workorderId)).thenReturn(List.of());
        when(invoiceClient.createInvoice(any(InvoiceCreationRequest.class)))
                .thenReturn(InvoiceGenerationResponse.builder()
                        .invoiceId(UUID.randomUUID())
                        .status("DRAFT")
                        .subtotal(new BigDecimal("210.0000"))
                        .taxAmount(BigDecimal.ZERO)
                        .totalAmount(new BigDecimal("210.0000"))
                        .build());
        when(workorderRepository.save(any(Workorder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        workorderInvoiceService.generateInvoice(workorderId, null);

        ArgumentCaptor<InvoiceCreationRequest> requestCaptor = ArgumentCaptor.forClass(InvoiceCreationRequest.class);
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
        UUID existingInvoiceId = UUID.randomUUID();
        Workorder workorder = completedWorkorder();
        workorder.setInvoiceId(existingInvoiceId);

        when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));
        when(workorderServiceRepository.findByWorkOrder_Id(workorderId)).thenReturn(List.of(serviceLine()));
        when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId)).thenReturn(List.of());
        when(workorderPartRepository.findByWorkorderId(workorderId)).thenReturn(List.of());

        InvoiceGenerationResponse response = workorderInvoiceService.generateInvoice(workorderId, null);

        assertThat(response.getInvoiceId()).isEqualTo(existingInvoiceId);
        assertThat(response.getStatus()).isEqualTo("DRAFT");
        assertThat(response.getWorkorderId()).isEqualTo(workorderId);
        assertThat(response.getEstimateId()).isEqualTo(estimateId);
        assertThat(response.getApprovalId()).isEqualTo(approvalId);
        verify(invoiceClient, never()).createInvoice(any());
    }

    @Test
    @DisplayName("generateInvoice handles race condition when idempotency key already registered")
    void generateInvoice_RaceCondition_ReturnsExistingInvoice() {
        Workorder workorder = completedWorkorder();
        when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));
        when(workorderServiceRepository.findByWorkOrder_Id(workorderId)).thenReturn(List.of(serviceLine()));
        when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId)).thenReturn(List.of(partLine()));
        when(workorderPartRepository.findByWorkorderId(workorderId)).thenReturn(List.of());

        UUID newInvoiceId = UUID.randomUUID();
        UUID existingInvoiceId = UUID.randomUUID();
        InvoiceGenerationResponse generated = InvoiceGenerationResponse.builder()
                .invoiceId(newInvoiceId)
                .status("DRAFT")
                .workorderId(workorderId)
                .estimateId(estimateId)
                .approvalId(approvalId)
                .subtotal(new BigDecimal("170.0000"))
                .taxAmount(new BigDecimal("10.0000"))
                .totalAmount(new BigDecimal("180.0000"))
                .createdAt(Instant.now())
                .build();

        when(invoiceClient.createInvoice(any(InvoiceCreationRequest.class))).thenReturn(generated);
        
        // First call to getExistingInvoiceId (early check) returns empty, second call (after collision) returns existing
        when(idempotencyService.getExistingInvoiceId("inv-key-race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingInvoiceId));
        
        // Simulate race condition: registerInvoiceKey throws DataIntegrityViolationException
        doThrow(new org.springframework.dao.DataIntegrityViolationException("Duplicate key"))
                .when(idempotencyService).registerInvoiceKey(eq("inv-key-race"), eq(newInvoiceId));

        InvoiceGenerationResponse response = workorderInvoiceService.generateInvoice(workorderId, "inv-key-race");

        // Should return the existing invoice, not the newly created one
        assertThat(response.getInvoiceId()).isEqualTo(existingInvoiceId);
        assertThat(response.getStatus()).isEqualTo("DRAFT");
        
        // Verify workorder invoiceId was NOT set to the new invoice (no save should happen in race condition)
        verify(workorderRepository, never()).save(any(Workorder.class));
        assertThat(workorder.getInvoiceId()).isNull(); // workorder should remain unchanged
    }

    private Workorder completedWorkorder() {
        return Workorder.builder()
                .id(workorderId)
                .estimateId(estimateId)
                .approvalId(approvalId)
                .status(WorkorderStatus.COMPLETED)
                .updatedAt(LocalDateTime.now())
                .completedAt(Instant.now())
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
