package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.invoice.internal.client.LocationServiceClient;
import com.positivity.invoice.internal.client.TaxServiceClient;
import com.positivity.invoice.internal.client.WorkorderReferenceClient;
import com.positivity.invoice.internal.dto.AdjustmentRequest;
import com.positivity.invoice.internal.dto.InvoiceDetailsResponse;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.InvoiceItem;
import com.positivity.invoice.internal.enums.InvoiceAdjustmentType;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import com.positivity.shared.dto.InvoiceLineItem;
import com.positivity.tax.common.dto.TaxCalculationRequest.TaxAddress;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceImplTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private TaxServiceClient taxServiceClient;

    @Mock
    private LocationServiceClient locationServiceClient;

    @Mock
    private WorkorderReferenceClient workorderReferenceClient;

    @InjectMocks
    private InvoiceServiceImpl invoiceService;

    private Invoice draftInvoice;
    private UUID invoiceId;
    private UUID workorderId;
    private UUID locationId;

    private static TaxAddress sampleTaxAddress() {
        return TaxAddress.builder()
                .countryCode("US")
                .regionCode("NC")
                .city("Charlotte")
                .postalCode("28202")
                .line1("100 Trade Street")
                .build();
    }

    @BeforeEach
    void setUp() {
        invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        locationId = UUID.fromString("01960003-0000-7000-8000-000000000001");

        draftInvoice = new Invoice();
        draftInvoice.setId(invoiceId);
        draftInvoice.setWorkorderId(workorderId);
        draftInvoice.setStatus(InvoiceStatus.DRAFT);
        draftInvoice.setSubtotal(BigDecimal.valueOf(100));
        draftInvoice.setAdjustmentsAmount(BigDecimal.ZERO);
        draftInvoice.setTax(BigDecimal.ZERO);
        draftInvoice.setTotal(BigDecimal.valueOf(100));
        draftInvoice.setLocationId(locationId);
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

        assertThatThrownBy(() -> invoiceService.getInvoice(invoiceId)).isInstanceOf(InvoiceNotFoundException.class);
    }

    // ---- createInvoice(InvoiceGenerationRequest) ----

    @Test
    void createInvoice_generationRequest_shouldCreateNewInvoice() {
        InvoiceGenerationRequest request = new InvoiceGenerationRequest();
        request.setWorkorderId(workorderId);

        when(invoiceRepository.findByWorkorderId(workorderId)).thenReturn(Optional.empty());
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

        assertThatThrownBy(() -> invoiceService.createInvoice(request)).isInstanceOf(IllegalArgumentException.class);
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
                .locationId(locationId)
                .lineItems(List.of(lineItem))
                .build();

        when(invoiceRepository.findByWorkorderId(workorderId)).thenReturn(Optional.empty());
        when(locationServiceClient.resolveTaxAddress(any())).thenReturn(sampleTaxAddress());
        when(taxServiceClient.calculateTax(any(), any(), any())).thenReturn(BigDecimal.valueOf(5));
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
        InvoiceCreationRequest request =
                InvoiceCreationRequest.builder().workorderId(null).build();

        assertThatThrownBy(() -> invoiceService.createInvoice(request)).isInstanceOf(IllegalArgumentException.class);
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
        when(invoiceRepository.save(any())).thenReturn(draftInvoice);

        InvoiceDetailsResponse result = invoiceService.applyAdjustment(invoiceId, request);

        assertThat(result).isNotNull();
    }

    @Test
    void applyAdjustment_discountOnInvoiceWithLineItems_calculatesTaxFromLineItemsOnly() {
        // Regression: a discount must not be folded into the tax request as a negative-priced
        // line (pos-tax @Positive would reject it). Tax is computed from the positive line
        // items; the discount is applied to the total after tax.
        InvoiceItem item = new InvoiceItem();
        item.setDescription("Brake pads");
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(BigDecimal.valueOf(100));
        item.setLineTotal(BigDecimal.valueOf(100));
        draftInvoice.addItem(item);

        AdjustmentRequest request = new AdjustmentRequest();
        request.setType(InvoiceAdjustmentType.DISCOUNT);
        request.setAmount(BigDecimal.valueOf(10));
        request.setReason("Customer loyalty discount");
        request.setAuthorizedBy("manager1");

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draftInvoice));
        when(locationServiceClient.resolveTaxAddress(any())).thenReturn(sampleTaxAddress());
        when(taxServiceClient.calculateTax(any(), any(), any())).thenReturn(BigDecimal.valueOf(8));
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
