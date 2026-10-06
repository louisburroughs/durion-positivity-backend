package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.UnpaidWalkInSalesResponse;
import com.positivity.accounting.internal.dto.WalkInOpenInvoice;
import com.positivity.accounting.internal.entity.ExtCustomerParty;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ExtLocationReplica;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import com.positivity.accounting.internal.enums.WalkInResolution;
import com.positivity.accounting.internal.repository.ExtCustomerPartyRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.ExtLocationReplicaRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The unpaid walk-in sales read (#2508): its assembly over the shared receivable rules, and the
 * business day computed with a fixed {@link Clock} across zones and the DST change (§9.5a).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UnpaidWalkInSalesServiceImpl (#2508)")
class UnpaidWalkInSalesServiceImplTest {

    private static final UUID CASH = UUID.fromString("00000000-0000-7000-8000-00000000ca5e");
    private static final UUID CHICAGO = UUID.fromString("00000000-0000-7000-8000-0000000000c1");
    private static final UUID NO_ZONE = UUID.fromString("00000000-0000-7000-8000-0000000000c2");

    @Mock
    private ExtCustomerPartyRepository customerPartyRepository;

    @Mock
    private ExtInvoiceRepository extInvoiceRepository;

    @Mock
    private ExtLocationReplicaRepository locationRepository;

    @Mock
    private ReceivablePaymentRepository receivablePaymentRepository;

    @Mock
    private InvoiceBalanceCalculator invoiceBalanceCalculator;

    private final List<ExtInvoice> invoices = new ArrayList<>();
    private final Map<UUID, BigDecimal> balances = new LinkedHashMap<>();
    private int invoiceCounter;

    @BeforeEach
    void setUp() {
        lenient()
                .when(customerPartyRepository.findByHouseAccount("CASH_SALE"))
                .thenReturn(List.of(ExtCustomerParty.builder()
                        .partyId(CASH)
                        .partyType("COMMERCIAL")
                        .customerNumber("CASH")
                        .displayName("Walk-in customer")
                        .houseAccount("CASH_SALE")
                        .status("ACTIVE")
                        .build()));
        lenient()
                .when(extInvoiceRepository.findByPartyIdInAndStatusIn(
                        List.of(CASH.toString()), InvoiceBalanceCalculator.AR_ELIGIBLE_STATUSES))
                .thenReturn(invoices);
        lenient().when(invoiceBalanceCalculator.balancesDue(anyCollection())).thenReturn(balances);
        lenient()
                .when(locationRepository.findAllById(any()))
                .thenReturn(List.of(
                        ExtLocationReplica.builder()
                                .locationId(CHICAGO)
                                .code("LOC-107")
                                .timezone("America/Chicago")
                                .active(true)
                                .build(),
                        ExtLocationReplica.builder()
                                .locationId(NO_ZONE)
                                .code("LOC-200")
                                .active(true)
                                .build()));
    }

    private UnpaidWalkInSalesServiceImpl serviceAt(String instant) {
        return new UnpaidWalkInSalesServiceImpl(
                customerPartyRepository,
                extInvoiceRepository,
                locationRepository,
                receivablePaymentRepository,
                invoiceBalanceCalculator,
                new LedgerCurrency("USD"),
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private ExtInvoice invoice(String number, UUID location, String finalizedAt, String total, String balanceDue) {
        ExtInvoice invoice = ExtInvoice.builder()
                .invoiceId(UUID.fromString(String.format("00000000-0000-7000-8000-%012d", ++invoiceCounter)))
                .invoiceNumber(number)
                .locationId(location)
                .partyId(CASH.toString())
                .status("FINALIZED")
                .total(new BigDecimal(total))
                .invoiceCreatedAt(Instant.parse(finalizedAt).minusSeconds(60))
                .finalizedAt(Instant.parse(finalizedAt))
                .updatedAt(Instant.parse(finalizedAt))
                .build();
        invoices.add(invoice);
        balances.put(invoice.getInvoiceId(), new BigDecimal(balanceDue));
        return invoice;
    }

    private static WalkInOpenInvoice row(UnpaidWalkInSalesResponse response, String number) {
        return response.getOpenInvoices().stream()
                .filter(open -> number.equals(open.getInvoiceNumber()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("AC1: in America/Chicago at 10:00 local, A paid, B 12.50 today, C 40.00 yesterday -> balance"
            + " 52.50, [C, B], C needs attention, needsAttention {1, 40.00}")
    void criterion1_balanceOrderAndNeedsAttention() {
        invoice("A", CHICAGO, "2026-10-06T13:00:00Z", "30.00", "0.0000");
        invoice("B", CHICAGO, "2026-10-06T14:00:00Z", "12.50", "12.5000");
        invoice("C", CHICAGO, "2026-10-05T20:00:00Z", "40.00", "40.0000");

        // 10:00 CDT (UTC-5) on 2026-10-06.
        UnpaidWalkInSalesResponse response = serviceAt("2026-10-06T15:00:00Z").read();

        assertThat(response.isHouseAccountKnown()).isTrue();
        assertThat(response.getCustomerNumber()).isEqualTo("CASH");
        assertThat(response.getCurrencyCode()).isEqualTo("USD");
        assertThat(response.getBalance()).isEqualByComparingTo("52.50");
        assertThat(response.getBalance().scale()).isEqualTo(2);
        assertThat(response.getOpenInvoices())
                .extracting(WalkInOpenInvoice::getInvoiceNumber)
                .containsExactly("C", "B");
        WalkInOpenInvoice c = row(response, "C");
        assertThat(c.isBusinessDayEnded()).isTrue();
        assertThat(c.getSaleDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(c.getLocationCode()).isEqualTo("LOC-107");
        assertThat(c.isTimezoneFallback()).isFalse();
        assertThat(c.getBalanceDue()).isEqualByComparingTo("40.00");
        assertThat(c.getResolutions()).containsExactly(WalkInResolution.COLLECT, WalkInResolution.CREDIT_MEMO);
        assertThat(row(response, "B").isBusinessDayEnded()).isFalse();
        assertThat(response.getNeedsAttention().getCount()).isEqualTo(1);
        assertThat(response.getNeedsAttention().getAmount()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("AC2: B is not flagged at 23:59 local; it is at 00:00 local the next day")
    void criterion2_localMidnight() {
        invoice("B", CHICAGO, "2026-10-06T14:00:00Z", "12.50", "12.50");

        // 23:59 CDT on 2026-10-06, then 00:00 CDT on 2026-10-07.
        assertThat(serviceAt("2026-10-07T04:59:00Z")
                        .read()
                        .getOpenInvoices()
                        .getFirst()
                        .isBusinessDayEnded())
                .isFalse();
        UnpaidWalkInSalesResponse midnight = serviceAt("2026-10-07T05:00:00Z").read();
        assertThat(midnight.getOpenInvoices().getFirst().isBusinessDayEnded()).isTrue();
        assertThat(midnight.getNeedsAttention().getCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("day end follows the DST change: on the fall-back day local midnight is 06:00Z, not 05:00Z")
    void businessDayAcrossTheDstChange() {
        // 00:30 CDT on 2026-11-01; clocks fall back at 02:00 that night, so the next local midnight is 06:00Z.
        invoice("D", CHICAGO, "2026-11-01T05:30:00Z", "20.00", "20.00");

        assertThat(serviceAt("2026-11-02T05:30:00Z")
                        .read()
                        .getOpenInvoices()
                        .getFirst()
                        .isBusinessDayEnded())
                .as("23:30 CST on 2026-11-01: the sale's day is still open")
                .isFalse();
        assertThat(serviceAt("2026-11-02T06:00:00Z")
                        .read()
                        .getOpenInvoices()
                        .getFirst()
                        .isBusinessDayEnded())
                .isTrue();
    }

    @Test
    @DisplayName("AC3: a location with no time zone computes day end in UTC and is marked timezoneFallback; so does"
            + " an invoice with no location")
    void criterion3_utcFallback() {
        invoice("E", NO_ZONE, "2026-10-06T23:30:00Z", "15.00", "15.00");
        invoice("F", null, "2026-10-06T23:40:00Z", "16.00", "16.00");

        // 00:00 UTC on 2026-10-07: in Chicago it would still be 2026-10-06.
        UnpaidWalkInSalesResponse response = serviceAt("2026-10-07T00:00:00Z").read();

        WalkInOpenInvoice e = row(response, "E");
        assertThat(e.isTimezoneFallback()).isTrue();
        assertThat(e.isBusinessDayEnded()).isTrue();
        assertThat(e.getSaleDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(e.getLocationCode()).isEqualTo("LOC-200");
        WalkInOpenInvoice f = row(response, "F");
        assertThat(f.isTimezoneFallback()).isTrue();
        assertThat(f.isBusinessDayEnded()).isTrue();
        assertThat(f.getLocationCode()).isNull();
        assertThat(response.getNeedsAttention().getAmount()).isEqualByComparingTo("31.00");
    }

    @Test
    @DisplayName("a time zone the JDK does not know falls back to UTC rather than failing the read")
    void invalidTimezoneFallsBack() {
        UUID broken = UUID.fromString("00000000-0000-7000-8000-0000000000c3");
        when(locationRepository.findAllById(any()))
                .thenReturn(List.of(ExtLocationReplica.builder()
                        .locationId(broken)
                        .code("LOC-300")
                        .timezone("Mars/Olympus_Mons")
                        .active(true)
                        .build()));
        invoice("G", broken, "2026-10-06T12:00:00Z", "10.00", "10.00");

        WalkInOpenInvoice g =
                serviceAt("2026-10-06T15:00:00Z").read().getOpenInvoices().getFirst();

        assertThat(g.isTimezoneFallback()).isTrue();
        assertThat(g.isBusinessDayEnded()).isFalse();
    }

    @Test
    @DisplayName("AC9: with no CASH party in the replica the read answers zero with houseAccountKnown = false")
    void criterion9_houseAccountUnknown() {
        when(customerPartyRepository.findByHouseAccount("CASH_SALE")).thenReturn(List.of());

        UnpaidWalkInSalesResponse response = serviceAt("2026-10-06T15:00:00Z").read();

        assertThat(response.isHouseAccountKnown()).isFalse();
        assertThat(response.getBalance()).isEqualByComparingTo("0.00");
        assertThat(response.getOpenInvoices()).isEmpty();
        assertThat(response.getUnappliedPayments()).isEmpty();
        assertThat(response.getNeedsAttention().getCount()).isZero();
        assertThat(response.getNeedsAttention().getAmount()).isEqualByComparingTo("0.00");
        assertThat(response.getCustomerNumber()).isNull();
        assertThat(response.getCurrencyCode()).isEqualTo("USD");
    }

    @Test
    @DisplayName(
            "lists CASH payments with money left unapplied, by the number of the invoice they were taken" + " against")
    void unappliedPayments() {
        ExtInvoice paid = invoice("INV-9", CHICAGO, "2026-10-06T14:00:00Z", "45.00", "0.00");
        ReceivablePayment payment = new ReceivablePayment();
        payment.setPaymentId(UUID.fromString("00000000-0000-7000-8000-0000000000f1"));
        payment.setCustomerId(CASH);
        payment.setTotalAmount(new BigDecimal("50.00"));
        payment.setUnappliedAmount(new BigDecimal("5.0000"));
        payment.setStatus(ReceivablePaymentStatus.AVAILABLE);
        payment.setClearedAt(Instant.parse("2026-10-06T14:05:00Z"));
        payment.setSourceInvoiceId(paid.getInvoiceId());
        when(receivablePaymentRepository.findByCustomerIdInAndStatusOrderByClearedAtAscPaymentIdAsc(
                        anyCollection(), org.mockito.ArgumentMatchers.eq(ReceivablePaymentStatus.AVAILABLE)))
                .thenReturn(List.of(payment));
        when(extInvoiceRepository.findAllById(any())).thenReturn(List.of(paid));

        UnpaidWalkInSalesResponse response = serviceAt("2026-10-06T15:00:00Z").read();

        assertThat(response.getOpenInvoices()).isEmpty();
        assertThat(response.getBalance()).isEqualByComparingTo("0.00");
        assertThat(response.getUnappliedPayments()).singleElement().satisfies(unapplied -> {
            assertThat(unapplied.getPaymentReference()).isEqualTo("INV-9");
            assertThat(unapplied.getUnappliedAmount()).isEqualByComparingTo("5.00");
            assertThat(unapplied.getUnappliedAmount().scale()).isEqualTo(2);
            assertThat(unapplied.getReceivedAt()).isEqualTo(Instant.parse("2026-10-06T14:05:00Z"));
            assertThat(unapplied.getPaymentId()).isEqualTo(payment.getPaymentId());
        });
    }
}
