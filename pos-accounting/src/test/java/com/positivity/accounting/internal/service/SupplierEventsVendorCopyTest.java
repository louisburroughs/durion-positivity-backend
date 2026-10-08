package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.entity.SupplierInvoiceHold;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.repository.SupplierInvoiceHoldRepository;
import com.positivity.accounting.internal.repository.VendorBillReissueRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * The vendor copy and the vendor key in the supplier consumer (CAP:550 S24, #2517): AC 1, 2, 3, 4, 5 (EDI) and 15.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SupplierEventsListener — vendor copy and vendor key (S24)")
class SupplierEventsVendorCopyTest {

    private static final Instant NOW = Instant.parse("2026-10-08T09:00:00Z");
    private static final UUID VENDOR = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9a01");
    private static final UUID PROFILE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9a02");
    private static final String VENDOR_EVENT = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9b01";
    private static final String INVOICE_EVENT = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9b02";

    /** AC 15's fake registration number, in its separated and unseparated forms. Never printed. */
    private static final String FAKE_NUMBER = "000-00-1234";

    private static final String FAKE_NUMBER_BARE = "000001234";
    private static final String LAST4 = "Z9Q8";

    @Mock
    private ProcessedEventRepository processed;

    @Mock
    private VendorBillRepository bills;

    @Mock
    private ExtSupplierVendorRepository copy;

    @Mock
    private SupplierInvoiceHoldRepository holds;

    @Mock
    private VendorBillReissueRepository reissues;

    @Mock
    private VendorBillLocks locks;

    @Mock
    private KafkaFactIngestionRecorder ingestion;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private SupplierEventsListener listener;
    private ListAppender<ILoggingEvent> logs;
    private Logger accountingLogger;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        ObjectProvider<MeterRegistry> noMeters = mock();
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> registry = mock(ObjectProvider.class);
        when(registry.getIfAvailable()).thenReturn(meters);
        listener = new SupplierEventsListener(
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper(),
                processed,
                bills,
                copy,
                holds,
                new LedgerCurrency("USD"),
                ingestion,
                new VendorBillDuplicateGuard(bills, noMeters),
                reissues,
                locks,
                registry,
                mock(PlatformTransactionManager.class));
        when(processed.existsById(any())).thenReturn(false);
        when(bills.findLiveDuplicate(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(bills.saveAndFlush(any())).thenAnswer(inv -> {
            VendorBill bill = inv.getArgument(0);
            if (bill.getVendorBillId() == null) {
                bill.setVendorBillId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9c01"));
            }
            return bill;
        });
        lenient().when(copy.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        accountingLogger = (Logger) LoggerFactory.getLogger("com.positivity.accounting");
        previousLevel = accountingLogger.getLevel();
        accountingLogger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        accountingLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        accountingLogger.detachAppender(logs);
        accountingLogger.setLevel(previousLevel);
    }

    // ---- facts --------------------------------------------------------------------------------------------

    private static String vendorFact(int schemaVersion, long version, String status, String registration) {
        return """
            {"eventId":"%s","eventType":"supplier.vendor.updated","schemaVersion":%d,"aggregateVersion":%d,
             "aggregateId":"%s","payload":{
              "vendorId":"%s","vendorNumber":"V-000123","legalName":"Michelin Deutschland GmbH",
              "displayName":"Michelin Deutschland","taxRegistrations":[%s],
              "remitTo":{"payeeName":"Michelin","addressLine1":"1 Allee","city":"Karlsruhe","region":"BW",
                "postalCode":"76185","countryCode":"DE"},
              "remitToVersion":2,"defaultPaymentTerms":"NET30","defaultCurrency":"USD","status":"%s",
              "createdBy":"u.creator","createdAt":"2026-10-01T00:00:00Z","occurredAt":"2026-10-08T08:00:00Z"}}
            """.formatted(VENDOR_EVENT, schemaVersion, version, VENDOR, VENDOR, registration, status);
    }

    /** A version 1 fact as pos-supplier published it before #2621: the registration's full number. */
    private static String v1Fact() {
        return vendorFact(1, 1, "ACTIVE", "{\"scheme\":\"SSN\",\"number\":\"" + FAKE_NUMBER + "\",\"region\":null}");
    }

    private static String v2Fact(long version, String status) {
        return vendorFact(2, version, status, "{\"scheme\":\"EIN\",\"region\":null,\"last4\":\"" + LAST4 + "\"}");
    }

    private static String invoice(String eventId, String vendorIdJson, String gross, String net, String tax) {
        return """
            {"eventId":"%s","eventType":"supplier.invoice.received","payload":{
              "vendorProfileId":"%s","supplierRef":"michelin-de","vendorInvoiceNumber":"INV-77",
              "invoiceDate":"2026-10-06","type":"INVOICE","currency":"USD",
              "totalNetAmount":%s,"totalTaxAmount":%s,"totalGrossAmount":%s,
              "vendorOrderReference":null,"occurredAt":"2026-10-08T08:00:00Z","lines":[]%s}}
            """.formatted(eventId, PROFILE, net, tax, gross, vendorIdJson);
    }

    private static String invoiceFor(UUID vendorId) {
        return invoice(INVOICE_EVENT, ",\"vendorId\":\"" + vendorId + "\"", "288.00", "240.00", "48.00");
    }

    private static ExtSupplierVendor vendor(String status) {
        ExtSupplierVendor vendor = new ExtSupplierVendor();
        vendor.setVendorId(VENDOR);
        vendor.setVendorNumber("V-000123");
        vendor.setDisplayName("Michelin Deutschland");
        vendor.setStatus(status);
        vendor.setRemitToVersion(2);
        vendor.setCreatedBy("u.creator");
        vendor.setAggregateVersion(5);
        return vendor;
    }

    private ProcessedEvent mark() {
        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processed).save(captor.capture());
        return captor.getValue();
    }

    private VendorBill createdBill() {
        ArgumentCaptor<VendorBill> captor = ArgumentCaptor.forClass(VendorBill.class);
        verify(bills).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private void assertNoLogLineContains(String... secrets) {
        // Compared against constants and never printed: a failure names the count, not the line.
        long leaking = logs.list.stream()
                .map(event -> event.getFormattedMessage()
                        + (event.getThrowableProxy() == null
                                ? ""
                                : event.getThrowableProxy().getMessage()))
                .filter(line -> List.of(secrets).stream().anyMatch(line::contains))
                .count();
        assertThat(leaking).as("log lines carrying a registration value").isZero();
    }

    // ---- AC 1, AC 15: the copy and the schema-version gate -----------------------------------------------

    @Nested
    @DisplayName("the vendor copy (AC 1, AC 15)")
    class VendorCopy {

        @Test
        @DisplayName("AC 15: a schemaVersion 1 fact is marked (owner supplier), counted and skipped, never logged")
        void v1FactIsSkipped() {
            listener.onSupplierEvent(v1Fact());

            verify(copy, never()).saveAndFlush(any());
            verify(copy, never()).findById(any());
            assertThat(mark().getOwner()).isEqualTo("supplier");
            assertThat(meters.get("accounting.supplier_vendor.skipped")
                            .tag("eventType", "supplier.vendor.updated")
                            .tag("schemaVersion", "1")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
            assertThat(meters.get("accounting.supplier_vendor.skipped")
                            .meter()
                            .getId()
                            .getTags())
                    .hasSize(2);
            assertNoLogLineContains(FAKE_NUMBER, FAKE_NUMBER_BARE);
        }

        @Test
        @DisplayName(
                "AC 1, AC 15: a schemaVersion 2 fact is copied with {scheme, region, last4}; last4 is never logged")
        void v2FactIsCopied() {
            when(copy.findById(VENDOR)).thenReturn(Optional.empty());

            listener.onSupplierEvent(v2Fact(3, "INACTIVE"));

            ArgumentCaptor<ExtSupplierVendor> captor = ArgumentCaptor.forClass(ExtSupplierVendor.class);
            verify(copy).saveAndFlush(captor.capture());
            ExtSupplierVendor saved = captor.getValue();
            assertThat(saved.getVendorId()).isEqualTo(VENDOR);
            assertThat(saved.getVendorNumber()).isEqualTo("V-000123");
            assertThat(saved.getStatus()).isEqualTo("INACTIVE");
            assertThat(saved.getRemitToVersion()).isEqualTo(2);
            assertThat(saved.getAggregateVersion()).isEqualTo(3);
            assertThat(saved.getCreatedBy()).isEqualTo("u.creator");
            assertThat(saved.getTaxRegistrations()).hasSize(1);
            assertThat(saved.getTaxRegistrations().getFirst())
                    .containsEntry("scheme", "EIN")
                    .containsEntry("last4", LAST4)
                    .containsKey("region")
                    .doesNotContainKey("number");
            assertThat(saved.toString()).doesNotContain(LAST4);
            assertThat(mark().getOwner()).isEqualTo("supplier");
            assertNoLogLineContains(LAST4);
        }

        @Test
        @DisplayName("AC 1: an older aggregateVersion changes nothing; an equal one re-applies")
        void versionGuard() {
            when(copy.findById(VENDOR)).thenReturn(Optional.of(vendor("ACTIVE")));

            listener.onSupplierEvent(v2Fact(4, "INACTIVE"));
            verify(copy, never()).saveAndFlush(any());

            listener.onSupplierEvent(v2Fact(5, "INACTIVE").replace(VENDOR_EVENT, INVOICE_EVENT));
            verify(copy).saveAndFlush(any());
        }

        @Test
        @DisplayName("a database failure writing the copy propagates unmarked (ADR-0044)")
        void databaseFailurePropagatesUnmarked() {
            when(copy.findById(VENDOR)).thenReturn(Optional.empty());
            when(copy.saveAndFlush(any())).thenThrow(new DataAccessResourceFailureException("connection lost"));

            assertThatThrownBy(() -> listener.onSupplierEvent(v2Fact(1, "ACTIVE")))
                    .isInstanceOf(DataAccessResourceFailureException.class);
            verify(processed, never()).save(any());
        }

        @Test
        @DisplayName("a constraint refusal propagates with the constraint only, never the failing row (ADR-0072)")
        void constraintRefusalIsSanitised() {
            when(copy.findById(VENDOR)).thenReturn(Optional.empty());
            when(copy.saveAndFlush(any()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "Failing row contains (... " + LAST4 + " ...)"));

            assertThatThrownBy(() -> listener.onSupplierEvent(v2Fact(1, "ACTIVE")))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                    .satisfies(e -> {
                        assertThat(e.getMessage()).doesNotContain(LAST4);
                        assertThat(e.getCause()).isNull();
                    });
            verify(processed, never()).save(any());
        }

        @Test
        @DisplayName("every supplier fact is marked with owner supplier, an ignored type included")
        void ignoredTypeIsMarkedSupplier() {
            listener.onSupplierEvent(
                    "{\"eventId\":\"" + VENDOR_EVENT + "\",\"eventType\":\"supplier.catalog.updated\",\"payload\":{}}");

            assertThat(mark().getOwner()).isEqualTo("supplier");
        }
    }

    // ---- AC 2-5: the vendor key -----------------------------------------------------------------------------

    @Nested
    @DisplayName("the vendor key (AC 2-5)")
    class VendorKey {

        @Test
        @DisplayName("AC 2: the bill names the fact's vendorId, not the profile, with the copy's display name")
        void billKeysOnVendorId() {
            when(copy.findById(VENDOR)).thenReturn(Optional.of(vendor("ACTIVE")));

            listener.onSupplierEvent(invoiceFor(VENDOR));

            VendorBill bill = createdBill();
            assertThat(bill.getVendorId()).isEqualTo(VENDOR).isNotEqualTo(PROFILE);
            assertThat(bill.getVendorName()).isEqualTo("Michelin Deutschland");
            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.PENDING_RECEIPT_MATCH);
            // The duplicate rule keys on the vendor (S0): its lookup names VENDOR.
            verify(bills).findLiveDuplicate(eq(VENDOR), any(), any(), any(), any());
            assertThat(mark().getOwner()).isEqualTo("supplier");
        }

        @Test
        @DisplayName("AC 3: no vendorId: no bill, a VENDOR_ID_MISSING hold with the payload, the event marked")
        void missingVendorIdIsHeld() {
            listener.onSupplierEvent(invoice(INVOICE_EVENT, "", "288.00", "240.00", "48.00"));

            verify(bills, never()).saveAndFlush(any());
            ArgumentCaptor<SupplierInvoiceHold> captor = ArgumentCaptor.forClass(SupplierInvoiceHold.class);
            verify(holds).save(captor.capture());
            SupplierInvoiceHold hold = captor.getValue();
            assertThat(hold.getReason()).isEqualTo(SupplierInvoiceHold.Reason.VENDOR_ID_MISSING);
            assertThat(hold.getEventId()).isEqualTo(UUID.fromString(INVOICE_EVENT));
            assertThat(hold.getSupplierInvoiceRef()).isEqualTo("INV-77");
            assertThat(hold.getVendorId()).isNull();
            assertThat(hold.getPayload()).contains("INV-77").contains(PROFILE.toString());
            assertThat(mark().getEventId()).isEqualTo(INVOICE_EVENT);
            verify(ingestion, never()).record(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("AC 4: held before the vendor is copied, then created once with its ingestion record")
        void heldThenReleased() {
            when(copy.findById(VENDOR)).thenReturn(Optional.empty());
            listener.onSupplierEvent(invoiceFor(VENDOR));
            ArgumentCaptor<SupplierInvoiceHold> held = ArgumentCaptor.forClass(SupplierInvoiceHold.class);
            verify(holds).save(held.capture());
            SupplierInvoiceHold hold = held.getValue();
            assertThat(hold.getReason()).isEqualTo(SupplierInvoiceHold.Reason.VENDOR_NOT_IN_COPY);
            assertThat(hold.getVendorId()).isEqualTo(VENDOR);
            verify(bills, never()).saveAndFlush(any());

            // The vendor fact arrives: the copy is written, then the hold is released through the bill creation.
            UUID holdId = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9d01");
            hold.setHoldId(holdId);
            when(holds.findByVendorIdAndReasonAndReleasedAtIsNullOrderByReceivedAtAscHoldIdAsc(
                            VENDOR, SupplierInvoiceHold.Reason.VENDOR_NOT_IN_COPY))
                    .thenReturn(List.of(hold));
            // Released under the hold's row lock (only lockByHoldId is stubbed: an unlocked read finds nothing).
            when(holds.lockByHoldId(holdId)).thenReturn(Optional.of(hold));
            when(copy.findById(VENDOR)).thenReturn(Optional.empty(), Optional.of(vendor("ACTIVE")));
            listener.onSupplierEvent(v2Fact(5, "ACTIVE"));

            VendorBill bill = createdBill();
            assertThat(bill.getVendorId()).isEqualTo(VENDOR);
            assertThat(bill.getOriginEventId()).isEqualTo(UUID.fromString(INVOICE_EVENT));
            verify(ingestion)
                    .record(
                            eq("pos-supplier"),
                            eq("supplier.invoice.received"),
                            eq(INVOICE_EVENT),
                            eq(bill.getVendorBillId()),
                            any(),
                            any(),
                            any());
            assertThat(hold.getReleasedAt()).isEqualTo(NOW);
            assertThat(hold.getReleasedBillId()).isEqualTo(bill.getVendorBillId());
            assertThat(meters.get("accounting.supplier_invoice.hold_released")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("AC 5: an inactive vendor's invoice is a MATCH_EXCEPTION naming the vendor as inactive")
        void inactiveVendorIsMatchException() {
            when(copy.findById(VENDOR)).thenReturn(Optional.of(vendor("INACTIVE")));

            listener.onSupplierEvent(invoiceFor(VENDOR));

            VendorBill bill = createdBill();
            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.MATCH_EXCEPTION);
            assertThat(bill.getRejectionReason()).isEqualTo("Vendor V-000123 is inactive");
        }

        @Test
        @DisplayName("ruling 3: inactive with an AW47 totals gap keeps both explanations and the gap")
        void inactiveWithTotalsGap() {
            when(copy.findById(VENDOR)).thenReturn(Optional.of(vendor("INACTIVE")));

            listener.onSupplierEvent(
                    invoice(INVOICE_EVENT, ",\"vendorId\":\"" + VENDOR + "\"", "300.00", "240.00", "48.00"));

            VendorBill bill = createdBill();
            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.MATCH_EXCEPTION);
            assertThat(bill.getRejectionReason()).startsWith("Vendor V-000123 is inactive. ");
            assertThat(VendorBillTotals.of(bill))
                    .hasValueSatisfying(t -> assertThat(t.reconciled()).isFalse());
        }

        @Test
        @DisplayName(
                "an inactive vendor's invoice in a foreign currency is held for its currency, naming the vendor first")
        void inactiveForeignCurrencyNamesTheVendor() {
            when(copy.findById(VENDOR)).thenReturn(Optional.of(vendor("INACTIVE")));

            listener.onSupplierEvent(invoiceFor(VENDOR).replace("\"currency\":\"USD\"", "\"currency\":\"EUR\""));

            VendorBill bill = createdBill();
            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.CURRENCY_HOLD);
            assertThat(bill.getRejectionReason()).startsWith("Vendor V-000123 is inactive. Currency EUR");
        }
    }

    @Nested
    @DisplayName("hold release (S24 review items 5)")
    class HoldRelease {

        private SupplierInvoiceHold heldInvoice(String holdId, String eventId, String number) {
            SupplierInvoiceHold hold = new SupplierInvoiceHold();
            hold.setHoldId(UUID.fromString(holdId));
            hold.setEventId(UUID.fromString(eventId));
            hold.setVendorId(VENDOR);
            hold.setReason(SupplierInvoiceHold.Reason.VENDOR_NOT_IN_COPY);
            hold.setSupplierInvoiceRef(number);
            hold.setPayload(invoice(eventId, ",\"vendorId\":\"" + VENDOR + "\"", "288.00", "240.00", "48.00")
                    .replace("INV-77", number));
            when(holds.lockByHoldId(hold.getHoldId())).thenReturn(Optional.of(hold));
            return hold;
        }

        @Test
        @DisplayName("a hold found released under its lock creates no second bill")
        void alreadyReleasedUnderTheLock() {
            when(copy.findById(VENDOR)).thenReturn(Optional.of(vendor("ACTIVE")));
            SupplierInvoiceHold hold = heldInvoice(
                    "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9d11", "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9b11", "INV-81");
            hold.setReleasedAt(NOW.minusSeconds(60));
            when(holds.findByVendorIdAndReasonAndReleasedAtIsNullOrderByReceivedAtAscHoldIdAsc(
                            VENDOR, SupplierInvoiceHold.Reason.VENDOR_NOT_IN_COPY))
                    .thenReturn(List.of(hold));

            listener.releaseHolds(VENDOR);

            verify(bills, never()).saveAndFlush(any());
            verify(holds, never()).save(any());
            verify(ingestion, never()).record(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a failed release keeps its hold HELD, and the next hold is still released")
        void failedReleaseKeepsTheHoldAndGoesOn() {
            when(copy.findById(VENDOR)).thenReturn(Optional.of(vendor("ACTIVE")));
            SupplierInvoiceHold first = heldInvoice(
                    "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9d21", "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9b21", "INV-91");
            SupplierInvoiceHold second = heldInvoice(
                    "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9d22", "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9b22", "INV-92");
            when(holds.findByVendorIdAndReasonAndReleasedAtIsNullOrderByReceivedAtAscHoldIdAsc(
                            VENDOR, SupplierInvoiceHold.Reason.VENDOR_NOT_IN_COPY))
                    .thenReturn(List.of(first, second));
            when(bills.saveAndFlush(any()))
                    .thenThrow(new DataAccessResourceFailureException("connection lost"))
                    .thenAnswer(inv -> {
                        VendorBill bill = inv.getArgument(0);
                        bill.setVendorBillId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9c22"));
                        return bill;
                    });

            listener.releaseHolds(VENDOR);

            assertThat(first.getReleasedAt()).isNull();
            assertThat(first.getReleasedBillId()).isNull();
            assertThat(second.getReleasedAt()).isEqualTo(NOW);
            assertThat(second.getReleasedBillId()).isEqualTo(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9c22"));
            verify(holds).save(second);
            verify(holds, never()).save(first);
        }
    }
}
