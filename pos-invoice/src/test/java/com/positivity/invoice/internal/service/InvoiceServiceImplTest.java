package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.client.TaxServiceClient;
import com.positivity.invoice.internal.dto.AdjustmentRequest;
import com.positivity.invoice.internal.dto.InvoiceDetailsResponse;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.enums.InvoiceAdjustmentType;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import com.positivity.shared.dto.InvoiceLineItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceImplTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private TaxServiceClient taxServiceClient;

    @InjectMocks
    private InvoiceServiceImpl invoiceService;

    private Invoice draftInvoice;
    private UUID invoiceId;
    private UUID workorderId;

    @BeforeEach
    void setUp() {
        invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        draftInvoice = new Invoice();
        draftInvoice.setId(invoiceId);
        draftInvoice.setWorkorderId(workorderId);
        draftInvoice.setStatus(InvoiceStatus.DRAFT);
        draftInvoice.setSubtotal(BigDecimal.valueOf(100));
        draftInvoice.setAdjustmentsAmount(BigDecimal.ZERO);
        draftInvoice.setTax(BigDecimal.ZERO);
        draftInvoice.setTotal(BigDecimal.valueOf(100));
    }

    // ---- getInvoice ----

    @Test
    void getInvoice_shouldReturnDetailResponse_whenFound() {
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draftInvoice));

        InvoiceDetailsResponse result = invoiceService.getInvoice(invoiceId);

        assertThat(result).isNotNull();
        assertThat(result.getInvoiceId()).isEqualTo(invoiceId);
    }

    @Test
    void getInvoice_shouldThrow_whenNotFound() {
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.getInvoice(invoiceId))
                .isInstanceOf(InvoiceNotFoundException.class);
    }

    // ---- createInvoice(InvoiceGenerationRequest) ----

    @Test
    void createInvoice_generationRequest_shouldCreateNewInvoice() {
        InvoiceGenerationRequest request = new InvoiceGenerationRequest();
        request.setWorkorderId(workorderId);

        when(invoiceRepository.findByWorkorderId(workorderId)).thenReturn(Optional.empty());
        when(taxServiceClient.calculateTax(any(), any())).thenReturn(BigDecimal.ZERO);
        when(invoiceRepository.save(any())).thenReturn(draftInvoice);

        InvoiceGenerationResponse response = invoiceService.createInvoice(request);

        assertThat(response).isNotNull();
    }

    @Test
    void createInvoice_generationRequest_shouldReturnExistingInvoice() {
        InvoiceGenerationRequest request = new InvoiceGenerationRequest();
        request.setWorkorderId(workorderId);

        when(invoiceRepository.findByWorkorderId(workorderId)).thenReturn(Optional.of(draftInvoice));

        InvoiceGenerationResponse response = invoiceService.createInvoice(request);

        assertThat(response).isNotNull();
    }

    @Test
    void createInvoice_generationRequest_shouldThrow_whenWorkorderIdNull() {
        InvoiceGenerationRequest request = new InvoiceGenerationRequest();
        request.setWorkorderId(null);

        assertThatThrownBy(() -> invoiceService.createInvoice(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- createInvoice(InvoiceCreationRequest) ----

    @Test
    void createInvoice_creationRequest_shouldCreateNewInvoice() {
        InvoiceLineItem lineItem = new InvoiceLineItem();
        lineItem.setDescription("Labor");
        lineItem.setQuantity(BigDecimal.ONE);
        lineItem.setUnitPrice(BigDecimal.valueOf(50));
        lineItem.setAmount(BigDecimal.valueOf(50));

        InvoiceCreationRequest request = InvoiceCreationRequest.builder()
                .workorderId(workorderId)
                .lineItems(List.of(lineItem))
                .build();

        when(invoiceRepository.findByWorkorderId(workorderId)).thenReturn(Optional.empty());
        when(taxServiceClient.calculateTax(any(), any())).thenReturn(BigDecimal.valueOf(5));
        when(invoiceRepository.save(any())).thenReturn(draftInvoice);

        InvoiceGenerationResponse response = invoiceService.createInvoice(request);

        assertThat(response).isNotNull();
    }

    @Test
    void createInvoice_creationRequest_shouldReturnExistingInvoice() {
        InvoiceCreationRequest request = InvoiceCreationRequest.builder()
                .workorderId(workorderId)
                .lineItems(List.of())
                .build();

        when(invoiceRepository.findByWorkorderId(workorderId)).thenReturn(Optional.of(draftInvoice));

        InvoiceGenerationResponse response = invoiceService.createInvoice(request);

        assertThat(response).isNotNull();
    }

    @Test
    void createInvoice_creationRequest_shouldThrow_whenWorkorderIdNull() {
        InvoiceCreationRequest request = InvoiceCreationRequest.builder()
                .workorderId(null)
                .build();

        assertThatThrownBy(() -> invoiceService.createInvoice(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- applyAdjustment ----

    @Test
    void applyAdjustment_shouldAddAdjustmentToInvoice() {
        AdjustmentRequest request = new AdjustmentRequest();
        request.setType(InvoiceAdjustmentType.DISCOUNT);
        request.setAmount(BigDecimal.valueOf(10));
        request.setReason("Customer loyalty discount");
        request.setAuthorizedBy("manager1");

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draftInvoice));
        when(taxServiceClient.calculateTax(any(), any())).thenReturn(BigDecimal.valueOf(8));
        when(invoiceRepository.save(any())).thenReturn(draftInvoice);

        InvoiceDetailsResponse result = invoiceService.applyAdjustment(invoiceId, request);

        assertThat(result).isNotNull();
    }

    @Test
    void applyAdjustment_shouldThrow_whenInvoiceNotFound() {
        AdjustmentRequest request = new AdjustmentRequest();
        request.setType(InvoiceAdjustmentType.DISCOUNT);
        request.setAmount(BigDecimal.valueOf(10));
        request.setReason("reason");
        request.setAuthorizedBy("manager");

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.applyAdjustment(invoiceId, request))
                .isInstanceOf(InvoiceNotFoundException.class);
    }

    @Test
    void applyAdjustment_shouldThrow_whenInvoiceNotInDraftState() {
        draftInvoice.setStatus(InvoiceStatus.FINALIZED);
        AdjustmentRequest request = new AdjustmentRequest();
        request.setType(InvoiceAdjustmentType.DISCOUNT);
        request.setAmount(BigDecimal.valueOf(10));
        request.setReason("reason");
        request.setAuthorizedBy("manager");

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draftInvoice));

        assertThatThrownBy(() -> invoiceService.applyAdjustment(invoiceId, request))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void applyAdjustment_shouldThrow_whenAmountIsZero() {
        AdjustmentRequest request = new AdjustmentRequest();
        request.setType(InvoiceAdjustmentType.DISCOUNT);
        request.setAmount(BigDecimal.ZERO);
        request.setReason("reason");
        request.setAuthorizedBy("manager");

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draftInvoice));

        assertThatThrownBy(() -> invoiceService.applyAdjustment(invoiceId, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyAdjustment_shouldThrow_whenReasonIsBlank() {
        AdjustmentRequest request = new AdjustmentRequest();
        request.setType(InvoiceAdjustmentType.DISCOUNT);
        request.setAmount(BigDecimal.valueOf(10));
        request.setReason("   ");
        request.setAuthorizedBy("manager");

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draftInvoice));

        assertThatThrownBy(() -> invoiceService.applyAdjustment(invoiceId, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyAdjustment_shouldThrow_whenAuthorizedByIsBlank() {
        AdjustmentRequest request = new AdjustmentRequest();
        request.setType(InvoiceAdjustmentType.DISCOUNT);
        request.setAmount(BigDecimal.valueOf(10));
        request.setReason("reason");
        request.setAuthorizedBy("  ");

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draftInvoice));

        assertThatThrownBy(() -> invoiceService.applyAdjustment(invoiceId, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyAdjustment_shouldThrow_whenTypeIsNull() {
        AdjustmentRequest request = new AdjustmentRequest();
        request.setType(null);
        request.setAmount(BigDecimal.valueOf(10));
        request.setReason("reason");
        request.setAuthorizedBy("manager");

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draftInvoice));

        assertThatThrownBy(() -> invoiceService.applyAdjustment(invoiceId, request))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
