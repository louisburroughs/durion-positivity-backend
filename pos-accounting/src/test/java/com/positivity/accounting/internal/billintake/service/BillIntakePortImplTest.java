package com.positivity.accounting.internal.billintake.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.billintake.entity.BillIntakeItem;
import com.positivity.accounting.internal.billintake.enums.BillIntakeChannel;
import com.positivity.accounting.internal.billintake.enums.BillIntakeStatus;
import com.positivity.accounting.internal.billintake.enums.BillVendorResolution;
import com.positivity.accounting.internal.billintake.repository.BillIntakeItemRepository;
import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.service.AccountingSequenceLocker;
import com.positivity.accounting.internal.service.KafkaFactIngestionRecorder;
import com.positivity.accounting.internal.service.SupplierVendorCopies;
import com.positivity.accounting.internal.service.VendorBillReader;
import com.positivity.domainevents.supplier.SupplierInvoiceLine;
import com.positivity.domainevents.supplier.SupplierInvoiceReceivedV1;
import com.positivity.domainevents.supplier.SupplierInvoiceTax;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** {@link BillIntakePortImpl}: the machine-document branch (S25 Part A) and the goods receipt's new references. */
@DisplayName("BillIntakePortImpl (S25 #2518, Part A)")
class BillIntakePortImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T09:00:00Z"), ZoneOffset.UTC);
    private static final UUID VENDOR = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a02");
    private static final UUID INTAKE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7c77");
    private static final String EVENT = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7b01";

    private final BillIntakeItemRepository items = mock(BillIntakeItemRepository.class);
    private final VendorBillRepository bills = mock(VendorBillRepository.class);
    private final ExtSupplierVendorRepository vendorCopy = mock(ExtSupplierVendorRepository.class);
    private final KafkaFactIngestionRecorder ingestion = mock(KafkaFactIngestionRecorder.class);
    private final AccountingSequenceLocker sequenceLocker = mock(AccountingSequenceLocker.class);
    private final AccountingSequence counter = new AccountingSequence();
    private BillIntakePortImpl port;

    @BeforeEach
    void setUp() {
        counter.setNextValue(12L);
        when(sequenceLocker.lockOrProvision("BI-202610")).thenReturn(counter);
        when(items.saveAndFlush(any())).thenAnswer(inv -> {
            BillIntakeItem item = inv.getArgument(0);
            item.setIntakeId(INTAKE_ID);
            return item;
        });
        port = TestBillIntakePorts.builder()
                .with(CLOCK)
                .with(items)
                .with(bills)
                .with(vendorCopy)
                .with(ingestion)
                .with(sequenceLocker)
                .build();
    }

    private static SupplierInvoiceReceivedV1 fact(UUID vendorId, List<SupplierInvoiceTax> taxes) {
        return new SupplierInvoiceReceivedV1(
                UUID.randomUUID(),
                "ACME",
                "INV-77",
                LocalDate.of(2026, 10, 1),
                SupplierInvoiceReceivedV1.Type.INVOICE,
                "USD",
                new BigDecimal("240.00"),
                new BigDecimal("48.00"),
                new BigDecimal("288.00"),
                "PO-42",
                Instant.parse("2026-10-01T00:00:00Z"),
                List.of(new SupplierInvoiceLine(
                        1, null, "ART-9", 4, new BigDecimal("60.00"), new BigDecimal("240.00"), null)),
                vendorId,
                "EDIWHEEL_B",
                null,
                null,
                "NET30",
                taxes);
    }

    @Test
    @DisplayName("no vendorId: a READY_FOR_REVIEW EDI draft, vendor CHECK_THIS / NOT_SET_UP, fact values READ")
    void vendorlessFactBecomesADraft() {
        var outcome = port.acceptMachineDocument(
                fact(null, List.of(new SupplierInvoiceTax("vat ", new BigDecimal("48.00")))), EVENT);

        assertThat(outcome).isEqualTo(new BillIntakePort.MachineDocumentOutcome.Draft(INTAKE_ID, "BI-202610-0000012"));
        assertThat(counter.getNextValue()).isEqualTo(13L);
        ArgumentCaptor<BillIntakeItem> saved = ArgumentCaptor.forClass(BillIntakeItem.class);
        verify(items).saveAndFlush(saved.capture());
        BillIntakeItem draft = saved.getValue();
        assertThat(draft.getChannel()).isEqualTo(BillIntakeChannel.EDI);
        assertThat(draft.getStatus()).isEqualTo(BillIntakeStatus.READY_FOR_REVIEW);
        assertThat(draft.getVendorResolution()).isEqualTo(BillVendorResolution.NOT_SET_UP);
        assertThat(draft.getUploadedAt()).isEqualTo(Instant.now(CLOCK));
        assertThat(draft.getExtractionOutcome()).isNull();
        assertThat(draft.getReadBack().get("vendor"))
                .asString()
                .contains("CHECK_THIS")
                .contains("ACME");
        assertThat(draft.getReadBack().get("totalAmount"))
                .asString()
                .contains("288.00")
                .contains("READ");
        assertThat(draft.getReadBack().get("taxByType"))
                .asString()
                .contains("VAT")
                .contains("READ");
        assertThat(draft.getReadBack().get("lines")).asString().contains("ART-9");
        assertThat(draft.getReadBack().get("purchaseOrderNumber")).asString().contains("PO-42");
        // The ingestion record is keyed on the draft's id, nothing posts, and no bill is written.
        verify(ingestion).record(any(), any(), eq(EVENT), eq(INTAKE_ID), any(), any(), any());
        verify(bills, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a tax label that is not a tax type is CHECK_THIS on the draft")
    void unusableTaxLabelNeedsChecking() {
        port.acceptMachineDocument(
                fact(null, List.of(new SupplierInvoiceTax("VAT 19 %", new BigDecimal("48.00")))), EVENT);

        ArgumentCaptor<BillIntakeItem> saved = ArgumentCaptor.forClass(BillIntakeItem.class);
        verify(items).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getReadBack().get("taxByType")).asString().contains("CHECK_THIS");
    }

    @Test
    @DisplayName("a vendor not in the copy: nothing written, the listener holds the fact")
    void vendorNotInCopyWritesNothing() {
        when(vendorCopy.findById(VENDOR)).thenReturn(Optional.empty());

        var outcome = port.acceptMachineDocument(fact(VENDOR, null), EVENT);

        assertThat(outcome).isEqualTo(new BillIntakePort.MachineDocumentOutcome.VendorNotInCopy(VENDOR));
        verify(items, never()).saveAndFlush(any());
        verify(bills, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a vendor in the copy: the bill as before S25, created by the supplier feed, no draft")
    void vendorNamedMakesABill() {
        ExtSupplierVendor vendor = new ExtSupplierVendor();
        vendor.setVendorId(VENDOR);
        vendor.setVendorNumber("V-1");
        vendor.setDisplayName("Acme Parts Co");
        vendor.setStatus(ExtSupplierVendor.ACTIVE);
        when(vendorCopy.findById(VENDOR)).thenReturn(Optional.of(vendor));
        when(bills.saveAndFlush(any())).thenAnswer(inv -> {
            VendorBill bill = inv.getArgument(0);
            bill.setVendorBillId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7d01"));
            return bill;
        });
        com.positivity.accounting.internal.service.VendorBillDuplicateGuard guard =
                mock(com.positivity.accounting.internal.service.VendorBillDuplicateGuard.class);
        when(guard.findOriginal(any(), any(), any(), any())).thenReturn(Optional.empty());
        port = TestBillIntakePorts.builder()
                .with(CLOCK)
                .with(items)
                .with(bills)
                .with(vendorCopy)
                .with(guard)
                .with(ingestion)
                .build();

        var outcome = port.acceptMachineDocument(fact(VENDOR, null), EVENT);

        assertThat(outcome).isInstanceOf(BillIntakePort.MachineDocumentOutcome.Bill.class);
        ArgumentCaptor<VendorBill> saved = ArgumentCaptor.forClass(VendorBill.class);
        verify(bills).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getCreatedBy()).isEqualTo("supplier");
        assertThat(saved.getValue().getOriginEventType()).isEqualTo("SUPPLIER_INVOICE_RECEIVED");
        assertThat(saved.getValue().getPurchaseOrderNumber()).isEqualTo("PO-42");
        verify(items, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a goods receipt keeps its purchase-order number and delivery reference (ruling 8)")
    void goodsReceiptKeepsTheReferences() {
        UUID vendorId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        SupplierVendorCopies copies = mock(SupplierVendorCopies.class);
        ExtSupplierVendor vendor = new ExtSupplierVendor();
        vendor.setVendorId(vendorId);
        vendor.setVendorNumber("V-3");
        vendor.setDisplayName("Copy Vendor");
        vendor.setStatus(ExtSupplierVendor.ACTIVE);
        when(copies.requireForNewBusiness(vendorId, "A bill")).thenReturn(vendor);
        AccountingSequence billCounter = new AccountingSequence();
        billCounter.setNextValue(1L);
        when(sequenceLocker.lockOrProvision("BILL-202610")).thenReturn(billCounter);
        when(bills.saveAndFlush(any())).thenAnswer(inv -> {
            VendorBill bill = inv.getArgument(0);
            bill.setVendorBillId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7d02"));
            return bill;
        });
        VendorBillReader reader = mock(VendorBillReader.class);
        when(reader.read(any(VendorBill.class)))
                .thenReturn(com.positivity.accounting.internal.dto.VendorBillResponse.builder()
                        .build());
        port = TestBillIntakePorts.builder()
                .with(CLOCK)
                .with(bills)
                .with(copies)
                .with(reader)
                .with(sequenceLocker)
                .build();
        GoodsReceivedEvent event = GoodsReceivedEvent.builder()
                .eventId(UUID.randomUUID())
                .organizationId(UUID.randomUUID())
                .purchaseOrderId(UUID.randomUUID())
                .vendorId(vendorId)
                .receivedDate(LocalDateTime.of(2026, 10, 16, 8, 0))
                .purchaseOrderNumber("PO-42")
                .deliveryReference(" dn-088213 ")
                .lineItems(List.of(GoodsReceivedEvent.ReceivedLineItem.builder()
                        .productId(UUID.randomUUID())
                        .description("Brake pads")
                        .quantity(BigDecimal.TEN)
                        .unitPrice(new BigDecimal("24.99"))
                        .build()))
                .build();

        port.acceptGoodsReceipt(event);

        ArgumentCaptor<VendorBill> saved = ArgumentCaptor.forClass(VendorBill.class);
        verify(bills).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPurchaseOrderNumber()).isEqualTo("PO-42");
        assertThat(saved.getValue().getDeliveryReference()).isEqualTo("dn-088213");
        assertThat(saved.getValue().getDeliveryReferenceKey()).isEqualTo("DN088213");
    }
}
