package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import com.positivity.workorder.internal.config.InvoiceCommandPublisher;
import com.positivity.workorder.internal.entity.ExtInvoiceReplica;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.InvalidWorkorderStateException;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.repository.ExtInvoiceReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.internal.service.WorkorderInvoiceServiceImpl;
import java.math.BigDecimal;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for the async {@link WorkorderInvoiceServiceImpl} (ADR-0044 #900 Phase 5.4):
 * generation publishes an {@code invoice.generation-requested} command and returns PENDING;
 * already-invoiced workorders answer from the {@code ext_invoice} replica.
 */
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
    private ExtInvoiceReplicaRepository extInvoiceReplicaRepository;

    @SuppressWarnings("unchecked")
    private final ObjectProvider<InvoiceCommandPublisher> publisherProvider = mock(ObjectProvider.class);

    private final InvoiceCommandPublisher publisher = mock(InvoiceCommandPublisher.class);

    private WorkorderInvoiceServiceImpl service;

    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @BeforeEach
    void setUp() {
        service = new WorkorderInvoiceServiceImpl(
                workorderRepository,
                workorderServiceRepository,
                workorderPartRepository,
                extInvoiceReplicaRepository,
                publisherProvider);
    }

    private Workorder completedWorkorder() {
        return Workorder.builder()
                .id(WORKORDER_ID)
                .status(WorkorderStatus.COMPLETED)
                .build();
    }

    @Test
    @DisplayName("generateInvoice publishes the generation command with billable line items and returns PENDING")
    void generateInvoice_publishesCommandAndReturnsPending() {
        when(publisherProvider.getIfAvailable()).thenReturn(publisher);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(completedWorkorder()));
        when(workorderServiceRepository.findByWorkOrder_Id(WORKORDER_ID)).thenReturn(List.of());
        WorkorderPart part = WorkorderPart.builder()
                .id(UUID.randomUUID())
                .description("Brake pad")
                .quantity(new BigDecimal("2"))
                .unitPrice(new BigDecimal("25.00"))
                .status(WorkorderItemStatus.COMPLETED)
                .build();
        when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(WORKORDER_ID))
                .thenReturn(List.of(part));
        when(workorderPartRepository.findByWorkorderIdAndWorkOrderServiceIsNull(WORKORDER_ID))
                .thenReturn(List.of());

        InvoiceGenerationResponse response = service.generateInvoice(WORKORDER_ID, "key-1");

        assertThat(response.getStatus()).isEqualTo(WorkorderInvoiceService.STATUS_PENDING);
        assertThat(response.getInvoiceId()).isNull();
        assertThat(response.getWorkorderId()).isEqualTo(WORKORDER_ID);
        ArgumentCaptor<InvoiceCreationRequest> requestCaptor = ArgumentCaptor.forClass(InvoiceCreationRequest.class);
        verify(publisher).requestInvoiceGeneration(requestCaptor.capture(), eq("key-1"));
        InvoiceCreationRequest request = requestCaptor.getValue();
        assertThat(request.getWorkorderId()).isEqualTo(WORKORDER_ID);
        assertThat(request.getLineItems()).hasSize(1);
        assertThat(request.getLineItems().get(0).getAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("generateInvoice without an idempotency key falls back to the workorder id as command key")
    void generateInvoice_withoutKey_usesWorkorderId() {
        when(publisherProvider.getIfAvailable()).thenReturn(publisher);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(completedWorkorder()));
        when(workorderServiceRepository.findByWorkOrder_Id(WORKORDER_ID)).thenReturn(List.of());
        when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(WORKORDER_ID))
                .thenReturn(List.of());
        when(workorderPartRepository.findByWorkorderIdAndWorkOrderServiceIsNull(WORKORDER_ID))
                .thenReturn(List.of());

        service.generateInvoice(WORKORDER_ID, null);

        verify(publisher).requestInvoiceGeneration(any(InvoiceCreationRequest.class), eq(WORKORDER_ID.toString()));
    }

    @Test
    @DisplayName("generateInvoice answers from the ext_invoice replica when the workorder is already invoiced")
    void generateInvoice_alreadyInvoiced_answersFromReplica() {
        Workorder workorder = completedWorkorder();
        workorder.setInvoiceId(INVOICE_ID);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));
        when(extInvoiceReplicaRepository.findById(INVOICE_ID))
                .thenReturn(Optional.of(ExtInvoiceReplica.builder()
                        .invoiceId(INVOICE_ID)
                        .workorderId(WORKORDER_ID)
                        .status("DRAFT")
                        .subtotal(new BigDecimal("150.00"))
                        .tax(new BigDecimal("12.00"))
                        .total(new BigDecimal("162.00"))
                        .aggregateVersion(1L)
                        .build()));

        InvoiceGenerationResponse response = service.generateInvoice(WORKORDER_ID, null);

        assertThat(response.getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(response.getStatus()).isEqualTo("DRAFT");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("162.00");
        verify(publisher, never()).requestInvoiceGeneration(any(), any());
    }

    @Test
    @DisplayName("generateInvoice reports PENDING when linked but the replica row has not arrived yet")
    void generateInvoice_linkedWithoutReplicaRow_reportsPending() {
        Workorder workorder = completedWorkorder();
        workorder.setInvoiceId(INVOICE_ID);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));
        when(extInvoiceReplicaRepository.findById(INVOICE_ID)).thenReturn(Optional.empty());

        InvoiceGenerationResponse response = service.generateInvoice(WORKORDER_ID, null);

        assertThat(response.getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(response.getStatus()).isEqualTo(WorkorderInvoiceService.STATUS_PENDING);
    }

    @Test
    @DisplayName("generateInvoice rejects workorders that are not COMPLETED")
    void generateInvoice_notCompleted_throws() {
        Workorder workorder = completedWorkorder();
        workorder.setStatus(WorkorderStatus.WORK_IN_PROGRESS);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        assertThatThrownBy(() -> service.generateInvoice(WORKORDER_ID, null))
                .isInstanceOf(InvalidWorkorderStateException.class);
    }

    @Test
    @DisplayName("generateInvoice throws WorkorderNotFoundException for unknown workorders")
    void generateInvoice_unknownWorkorder_throws() {
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generateInvoice(WORKORDER_ID, null))
                .isInstanceOf(WorkorderNotFoundException.class);
    }

    @Test
    @DisplayName("generateInvoice fails clearly when the Kafka event feed is disabled")
    void generateInvoice_feedDisabled_throws() {
        when(publisherProvider.getIfAvailable()).thenReturn(null);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(completedWorkorder()));

        assertThatThrownBy(() -> service.generateInvoice(WORKORDER_ID, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(
                        e -> org.assertj.core.api.Assertions.assertThat(((ResponseStatusException) e).getStatusCode())
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                .hasMessageContaining("workorder.kafka.enabled");
    }

    @Test
    @DisplayName("generateInvoice excludes CANCELLED items from the command line items")
    void generateInvoice_excludesCancelledItems() {
        when(publisherProvider.getIfAvailable()).thenReturn(publisher);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(completedWorkorder()));
        when(workorderServiceRepository.findByWorkOrder_Id(WORKORDER_ID)).thenReturn(List.of());
        WorkorderPart cancelled = WorkorderPart.builder()
                .id(UUID.randomUUID())
                .description("Cancelled part")
                .status(WorkorderItemStatus.CANCELLED)
                .build();
        WorkorderPart billable = WorkorderPart.builder()
                .id(UUID.randomUUID())
                .description("Billable part")
                .quantity(BigDecimal.ONE)
                .unitPrice(BigDecimal.TEN)
                .status(WorkorderItemStatus.COMPLETED)
                .build();
        when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(WORKORDER_ID))
                .thenReturn(List.of(cancelled, billable));
        when(workorderPartRepository.findByWorkorderIdAndWorkOrderServiceIsNull(WORKORDER_ID))
                .thenReturn(List.of());

        service.generateInvoice(WORKORDER_ID, null);

        ArgumentCaptor<InvoiceCreationRequest> requestCaptor = ArgumentCaptor.forClass(InvoiceCreationRequest.class);
        verify(publisher).requestInvoiceGeneration(requestCaptor.capture(), any());
        assertThat(requestCaptor.getValue().getLineItems()).hasSize(1);
        assertThat(requestCaptor.getValue().getLineItems().get(0).getDescription())
                .isEqualTo("Billable part");
    }
}
