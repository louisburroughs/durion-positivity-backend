package com.positivity.accounting.internal.billintake.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.SupplierInvoiceHold;
import com.positivity.accounting.internal.repository.SupplierInvoiceHoldRepository;
import com.positivity.tenancy.TenantIterator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** The one-shot conversion of {@code VENDOR_ID_MISSING} holds into drafts (S25 Part A). */
@DisplayName("VendorlessHoldConverter (S25 #2518)")
class VendorlessHoldConverterTest {

    private static final Instant NOW = Instant.parse("2026-10-17T09:00:00Z");
    private static final UUID HOLD_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7e01");
    private static final UUID EVENT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7b09");
    private static final UUID INTAKE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7c88");

    private static final String PAYLOAD = """
            {"eventId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7b09","eventType":"supplier.invoice.received",
             "payload":{"vendorProfileId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a09","supplierRef":"ACME",
             "vendorInvoiceNumber":"INV-5","invoiceDate":"2026-09-30","type":"INVOICE","currency":"USD",
             "totalGrossAmount":10.00,"occurredAt":"2026-09-30T00:00:00Z","lines":[]}}
            """;

    private final SupplierInvoiceHoldRepository holds = mock(SupplierInvoiceHoldRepository.class);
    private final BillIntakePort port = mock(BillIntakePort.class);
    private VendorlessHoldConverter converter;
    private SupplierInvoiceHold hold;

    @BeforeEach
    void setUp() {
        hold = new SupplierInvoiceHold();
        hold.setHoldId(HOLD_ID);
        hold.setEventId(EVENT_ID);
        hold.setSupplierInvoiceRef("INV-5");
        hold.setReason(SupplierInvoiceHold.Reason.VENDOR_ID_MISSING);
        hold.setPayload(PAYLOAD);
        when(holds.findByReasonAndReleasedAtIsNullOrderByReceivedAtAscHoldIdAsc(
                        SupplierInvoiceHold.Reason.VENDOR_ID_MISSING))
                .thenReturn(List.of(hold));
        when(holds.lockByHoldId(HOLD_ID)).thenReturn(Optional.of(hold));
        converter = new VendorlessHoldConverter(
                mock(TenantIterator.class),
                holds,
                port,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(PlatformTransactionManager.class));
    }

    @Test
    @DisplayName("an open hold becomes a draft; the hold is released with no bill and names the draft")
    void convertsAnOpenHold() {
        when(port.acceptMachineDocument(any(), eq(EVENT_ID.toString())))
                .thenReturn(new BillIntakePort.MachineDocumentOutcome.Draft(INTAKE_ID, "BI-202610-0000001"));

        assertThat(converter.convertOpenHolds()).isEqualTo(1);

        assertThat(hold.getReleasedAt()).isEqualTo(NOW);
        assertThat(hold.getReleasedIntakeId()).isEqualTo(INTAKE_ID);
        assertThat(hold.getReleasedBillId()).isNull();
        verify(holds).save(hold);
    }

    @Test
    @DisplayName("a hold already released is left alone: a second run converts nothing")
    void idempotent() {
        hold.setReleasedAt(NOW);

        assertThat(converter.convertOpenHolds()).isZero();

        verify(port, never()).acceptMachineDocument(any(), any());
    }

    @Test
    @DisplayName("a conversion that fails leaves the hold held and does not stop the run")
    void failureLeavesTheHold() {
        when(port.acceptMachineDocument(any(), any())).thenThrow(new IllegalStateException("boom"));

        assertThat(converter.convertOpenHolds()).isZero();

        assertThat(hold.getReleasedAt()).isNull();
        verify(holds, never()).save(any());
    }
}
