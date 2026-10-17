package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.CashOutlookResponse;
import com.positivity.accounting.internal.dto.CashOutlookResponse.Point;
import com.positivity.accounting.internal.entity.ExtCustomerBillingRules;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ExtOrderPurchaseOrder;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.ExtCustomerBillingRulesRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.ExtOrderPurchaseOrderRepository;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The cash outlook of #2515 (CAP:550 S19; SPEC-accounting-workspace §4.2, §5.1 item 5; AW11): each inclusion and
 * exclusion, the estimated due dates, the overdue split, the low point, the cushion and the bills lane. The open bills
 * and their estimates are the real classes over mocked repositories, so the whole money-out rule runs.
 */
@DisplayName("CashOutlookService (#2515)")
class CashOutlookServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-17T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
    private static final UUID VENDOR = UUID.fromString("019a0000-0000-7000-8000-00000000c001");
    private static final UUID PO = UUID.fromString("019a0000-0000-7000-8000-00000000b001");
    private static final UUID CUSTOMER = UUID.fromString("019a0000-0000-7000-8000-00000000d001");
    private static final UUID CASH_PARTY = UUID.fromString("019a0000-0000-7000-8000-00000000ca51");

    private final CashAccounts cashAccounts = mock(CashAccounts.class);
    private final VendorBillRepository vendorBills = mock(VendorBillRepository.class);
    private final APPaymentAllocationRepository allocations = mock(APPaymentAllocationRepository.class);
    private final ExtOrderPurchaseOrderRepository purchaseOrders = mock(ExtOrderPurchaseOrderRepository.class);
    private final ExtSupplierVendorRepository vendors = mock(ExtSupplierVendorRepository.class);
    private final InvoiceBalanceCalculator balanceCalculator = mock(InvoiceBalanceCalculator.class);
    private final ExtInvoiceRepository extInvoices = mock(ExtInvoiceRepository.class);
    private final ExtCustomerBillingRulesRepository billingRules = mock(ExtCustomerBillingRulesRepository.class);
    private final CashAndPayablesSettings settings = mock(CashAndPayablesSettings.class);
    private final AccountingCalendarZoneResolver zoneResolver = mock(AccountingCalendarZoneResolver.class);
    private final LedgerCurrency ledgerCurrency = new LedgerCurrency("USD");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final List<VendorBill> bills = new ArrayList<>();
    private final Map<UUID, BigDecimal> allocated = new HashMap<>();
    private final List<ExtInvoice> invoices = new ArrayList<>();
    private final Map<UUID, BigDecimal> invoiceBalances = new HashMap<>();
    private LocalDate today = TODAY;
    private CashOutlookServiceImpl service;

    @BeforeEach
    void setUp() {
        when(zoneResolver.today()).thenAnswer(invocation -> today);
        when(zoneResolver.postingDate(any(Instant.class)))
                .thenAnswer(invocation -> LocalDate.ofInstant(invocation.getArgument(0), ZoneOffset.UTC));

        // In the bank 10,000.00 and waiting to be deposited 1,000.00; 600.00 in drawers must stay out.
        when(cashAccounts.read())
                .thenReturn(new CashAccounts.Components(
                        List.of(row("1000", "10000.00")),
                        List.of(row("1090", "1240.00"), row("1095", "-240.00")),
                        List.of(row("1080", "600.00")),
                        BigDecimal.ZERO,
                        null,
                        0));

        when(vendorBills.findByStatusIn(anyCollection())).thenAnswer(invocation -> List.copyOf(bills));
        when(allocations.sumAllocatedAmountByVendorBillIdIn(any()))
                .thenAnswer(invocation -> allocated.entrySet().stream()
                        .map(entry -> allocation(entry.getKey(), entry.getValue()))
                        .toList());
        when(purchaseOrders.findAllById(any())).thenReturn(List.of());
        when(vendors.findAllById(any())).thenReturn(List.of());
        when(settings.apDefaultTerms()).thenReturn("NET30");
        when(settings.cashSafetyCushion()).thenReturn(Optional.empty());

        when(balanceCalculator.walkInPartyIds()).thenReturn(Set.of(CASH_PARTY));
        when(extInvoices.findByStatusIn(any())).thenAnswer(invocation -> List.copyOf(invoices));
        when(balanceCalculator.balancesDue(any())).thenAnswer(invocation -> new HashMap<>(invoiceBalances));
        when(billingRules.findAllById(any())).thenReturn(List.of());

        EstimatedDueDateService estimates = new EstimatedDueDateService(purchaseOrders, vendors, settings);
        OpenVendorBills openBills = new OpenVendorBills(vendorBills, allocations, estimates, ledgerCurrency);
        service = new CashOutlookServiceImpl(
                cashAccounts,
                openBills,
                balanceCalculator,
                extInvoices,
                billingRules,
                settings,
                zoneResolver,
                ledgerCurrency,
                clock);
    }

    // ---- shape -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("days plus one points start today at the bank plus waiting cash, never drawers or the float")
    void startsFromTheBankAndWaitingCash() {
        CashOutlookResponse outlook = service.outlook(30);

        assertThat(outlook.projection()).isTrue();
        assertThat(outlook.points()).hasSize(31);
        assertThat(outlook.points().getFirst().date()).isEqualTo(TODAY);
        assertThat(outlook.points().getLast().date()).isEqualTo(TODAY.plusDays(30));
        assertThat(outlook.startingBalance()).isEqualByComparingTo("11000.00");
        assertThat(outlook.points()).allSatisfy(point -> assertThat(point.balance()).isEqualByComparingTo("11000.00"));
        assertThat(outlook.lowPoint().date()).as("the first of equal low points").isEqualTo(TODAY);
        assertThat(outlook.asOf()).isEqualTo(NOW);
        assertThat(outlook.currencyCode()).isEqualTo("USD");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 91, 365})
    @DisplayName("days outside 1 to 90 is a VALIDATION_ERROR on days")
    void daysOutOfRange(int days) {
        assertThatThrownBy(() -> service.outlook(days))
                .isInstanceOf(InvalidRequestParameterException.class)
                .satisfies(error -> assertThat(((InvalidRequestParameterException) error).getField())
                        .isEqualTo("days"));
    }

    @ParameterizedTest
    @CsvSource({"1,2", "90,91"})
    @DisplayName("the range limits are accepted")
    void daysAtTheLimits(int days, int points) {
        assertThat(service.outlook(days).points()).hasSize(points);
    }

    // ---- money out -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC 4: an approved bill with no due date goes out on its PO-terms estimate, flagged, never stored")
    void estimateFromPurchaseOrderTerms() {
        today = LocalDate.of(2026, 10, 5);
        VendorBill bill = approved("500.00", null, LocalDate.of(2026, 10, 1));
        bill.setPurchaseOrderId(PO);
        poTerms("NET15");

        CashOutlookResponse outlook = service.outlook(30);

        Point day = point(outlook, LocalDate.of(2026, 10, 16));
        assertThat(day.moneyOut()).isEqualByComparingTo("500.00");
        assertThat(day.estimatedMoneyOut()).isEqualByComparingTo("500.00");
        assertThat(day.balance()).isEqualByComparingTo("10500.00");
        assertThat(outlook.estimatedDueDates().amount()).isEqualByComparingTo("500.00");
        assertThat(outlook.estimatedDueDates().count()).isEqualTo(1);
        assertThat(bill.getDueDate()).as("the bill row still has no due date").isNull();
    }

    @Test
    @DisplayName("AC 4: with no PO the tenant default terms give 2026-10-31")
    void estimateFromTheTenantDefault() {
        today = LocalDate.of(2026, 10, 5);
        approved("500.00", null, LocalDate.of(2026, 10, 1));

        CashOutlookResponse outlook = service.outlook(30);

        assertThat(point(outlook, LocalDate.of(2026, 10, 31)).estimatedMoneyOut()).isEqualByComparingTo("500.00");
        assertThat(point(outlook, LocalDate.of(2026, 10, 30)).moneyOut()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("[M] the outlook never writes a bill: no save, and the due date stays null")
    void neverWritesTheDueDate() {
        VendorBill estimated = approved("500.00", null, TODAY.minusDays(10));
        VendorBill real = approved("200.00", TODAY.plusDays(3), TODAY.minusDays(10));

        service.outlook(30);
        service.outlook(90);

        assertThat(estimated.getDueDate()).isNull();
        assertThat(real.getDueDate()).isEqualTo(TODAY.plusDays(3).atStartOfDay());
        verify(vendorBills, never()).save(any());
        verify(vendorBills, never()).saveAll(any());
        verify(vendorBills, never()).saveAndFlush(any());
        verify(vendorBills, never()).saveAllAndFlush(any());
        verify(vendorBills, never()).delete(any());
    }

    @Test
    @DisplayName("a bill already past due counts on the first day")
    void pastDueBillCountsOnTheFirstDay() {
        approved("300.00", TODAY.minusDays(1), TODAY.minusDays(40));

        CashOutlookResponse outlook = service.outlook(30);

        assertThat(outlook.points().getFirst().moneyOut()).isEqualByComparingTo("300.00");
        assertThat(outlook.points().getFirst().balance()).isEqualByComparingTo("10700.00");
        assertThat(outlook.expectedOut()).isEqualByComparingTo("300.00");
        assertThat(outlook.lowPoint().date()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("an approved bill's open amount is its total less its live allocations")
    void openAmountIsNetOfAllocations() {
        VendorBill bill = approved("500.00", TODAY.plusDays(2), TODAY.minusDays(10));
        allocated.put(bill.getVendorBillId(), new BigDecimal("125.00"));

        CashOutlookResponse outlook = service.outlook(30);

        assertThat(outlook.expectedOut()).isEqualByComparingTo("375.00");
        assertThat(point(outlook, TODAY.plusDays(2)).moneyOut()).isEqualByComparingTo("375.00");
    }

    @Test
    @DisplayName("a fully allocated bill is left out")
    void fullyAllocatedBillIsLeftOut() {
        VendorBill bill = approved("500.00", TODAY.plusDays(2), TODAY.minusDays(10));
        allocated.put(bill.getVendorBillId(), new BigDecimal("500.00"));

        CashOutlookResponse outlook = service.outlook(30);

        assertThat(outlook.expectedOut()).isEqualByComparingTo("0.00");
        assertThat(outlook.billsLane().total()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("AC 5: unapproved bills are possibleOut; held, voided, rejected, paid and foreign bills are out entirely")
    void possibleOutAndExclusions() {
        bill(VendorBillStatus.AWAITING_APPROVAL, "300.00", null, TODAY.minusDays(2), "USD");
        bill(VendorBillStatus.PENDING_RECEIPT_MATCH, "50.00", null, TODAY.minusDays(2), "USD");
        bill(VendorBillStatus.MATCH_EXCEPTION, "25.00", null, TODAY.minusDays(2), "USD");
        bill(VendorBillStatus.CURRENCY_HOLD, "900.00", null, TODAY.minusDays(2), "USD");
        bill(VendorBillStatus.VOIDED, "11.00", null, TODAY.minusDays(2), "USD");
        bill(VendorBillStatus.REJECTED, "12.00", null, TODAY.minusDays(2), "USD");
        bill(VendorBillStatus.PAID, "13.00", null, TODAY.minusDays(2), "USD");
        bill(VendorBillStatus.APPROVED, "14.00", TODAY.plusDays(1), TODAY.minusDays(2), "EUR");

        CashOutlookResponse outlook = service.outlook(30);

        assertThat(outlook.possibleOut().amount()).isEqualByComparingTo("375.00");
        assertThat(outlook.possibleOut().count()).isEqualTo(3);
        assertThat(outlook.expectedOut()).isEqualByComparingTo("0.00");
        assertThat(outlook.points()).allSatisfy(point -> assertThat(point.moneyOut()).isEqualByComparingTo("0.00"));
        assertThat(outlook.billsLane().total()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("vendor credit notes are vendorCredits, not in the line")
    void vendorCredits() {
        approved("-120.00", TODAY.plusDays(4), TODAY.minusDays(3));
        bill(VendorBillStatus.AWAITING_APPROVAL, "-30.00", null, TODAY.minusDays(1), "USD");

        CashOutlookResponse outlook = service.outlook(30);

        assertThat(outlook.vendorCredits().amount()).isEqualByComparingTo("150.00");
        assertThat(outlook.vendorCredits().count()).isEqualTo(2);
        assertThat(outlook.expectedOut()).isEqualByComparingTo("0.00");
        assertThat(outlook.possibleOut().count()).isZero();
        assertThat(outlook.billsLane().total()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a bill due beyond the horizon is in the bills lane but not in the line")
    void beyondTheHorizon() {
        approved("400.00", TODAY.plusDays(20), TODAY.minusDays(3));

        CashOutlookResponse outlook = service.outlook(10);

        assertThat(outlook.expectedOut()).isEqualByComparingTo("0.00");
        assertThat(outlook.billsLane().dueLater().amount()).isEqualByComparingTo("400.00");
    }

    // ---- bills lane ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC 13: the bills lane splits approved bills by real due date; estimated bills stand apart")
    void billsLane() {
        approved("300.00", TODAY.minusDays(1), TODAY.minusDays(30));
        approved("200.00", TODAY.plusDays(3), TODAY.minusDays(30));
        approved("500.00", TODAY.plusDays(20), TODAY.minusDays(30));
        approved("150.00", null, TODAY);
        bill(VendorBillStatus.AWAITING_APPROVAL, "999.00", TODAY.plusDays(2), TODAY.minusDays(1), "USD");

        CashOutlookResponse.BillsLane lane = service.outlook(30).billsLane();

        assertThat(lane.overdue().amount()).isEqualByComparingTo("300.00");
        assertThat(lane.overdue().count()).isEqualTo(1);
        assertThat(lane.dueIn7Days().amount()).isEqualByComparingTo("200.00");
        assertThat(lane.dueLater().amount()).isEqualByComparingTo("500.00");
        assertThat(lane.dueDateEstimated().amount()).isEqualByComparingTo("150.00");
        assertThat(lane.dueDateEstimated().count()).isEqualTo(1);
        assertThat(lane.total()).isEqualByComparingTo("1150.00");
    }

    @Test
    @DisplayName("due today and due on the seventh day are both due in 7 days; the eighth day is later")
    void billsLaneBoundaries() {
        approved("10.00", TODAY, TODAY.minusDays(30));
        approved("20.00", TODAY.plusDays(7), TODAY.minusDays(30));
        approved("40.00", TODAY.plusDays(8), TODAY.minusDays(30));

        CashOutlookResponse.BillsLane lane = service.outlook(30).billsLane();

        assertThat(lane.dueIn7Days().amount()).isEqualByComparingTo("30.00");
        assertThat(lane.dueIn7Days().count()).isEqualTo(2);
        assertThat(lane.dueLater().amount()).isEqualByComparingTo("40.00");
        assertThat(lane.overdue().count()).isZero();
    }

    // ---- money in --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an open invoice comes in on its due date")
    void invoiceOnItsDueDate() {
        invoice("800.00", TODAY.plusDays(5), TODAY.minusDays(25), CUSTOMER);

        CashOutlookResponse outlook = service.outlook(30);

        assertThat(point(outlook, TODAY.plusDays(5)).moneyIn()).isEqualByComparingTo("800.00");
        assertThat(point(outlook, TODAY.plusDays(5)).balance()).isEqualByComparingTo("11800.00");
        assertThat(outlook.expectedIn()).isEqualByComparingTo("800.00");
        assertThat(outlook.overdueIn().count()).isZero();
    }

    @Test
    @DisplayName("AC 6: an overdue invoice is overdueIn, not in the line")
    void overdueInvoiceIsNotCollected() {
        invoice("200.00", TODAY.minusDays(1), TODAY.minusDays(31), CUSTOMER);

        CashOutlookResponse outlook = service.outlook(30);

        assertThat(outlook.overdueIn().amount()).isEqualByComparingTo("200.00");
        assertThat(outlook.overdueIn().count()).isEqualTo(1);
        assertThat(outlook.expectedIn()).isEqualByComparingTo("0.00");
        assertThat(outlook.points()).allSatisfy(point -> assertThat(point.moneyIn()).isEqualByComparingTo("0.00"));
    }

    @Test
    @DisplayName("an invoice with no due date is due on its finalization day plus the customer's terms")
    void invoiceDueFromCustomerTerms() {
        invoice("100.00", null, TODAY.minusDays(3), CUSTOMER);
        when(billingRules.findAllById(any())).thenReturn(List.of(rules("NET30")));

        CashOutlookResponse outlook = service.outlook(60);

        assertThat(point(outlook, TODAY.plusDays(27)).moneyIn()).isEqualByComparingTo("100.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DUE_ON_RECEIPT", "Due on Receipt", "2/10 net 30 EOM", "", "Cash"})
    @DisplayName("due on receipt, and terms that do not parse, fall due on the finalization day")
    void invoiceDueOnReceipt(String terms) {
        invoice("100.00", null, TODAY, CUSTOMER);
        when(billingRules.findAllById(any())).thenReturn(List.of(rules(terms)));

        CashOutlookResponse outlook = service.outlook(30);

        assertThat(outlook.points().getFirst().moneyIn()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("an invoice with no due date and no terms at all falls due on receipt")
    void invoiceWithoutTerms() {
        invoice("100.00", null, TODAY, CUSTOMER);

        assertThat(service.outlook(30).points().getFirst().moneyIn()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("an invoice whose terms already ran out is overdue")
    void invoiceWhoseTermsRanOut() {
        invoice("100.00", null, TODAY.minusDays(10), CUSTOMER);
        when(billingRules.findAllById(any())).thenReturn(List.of(rules("NET5")));

        CashOutlookResponse outlook = service.outlook(30);

        assertThat(outlook.overdueIn().amount()).isEqualByComparingTo("100.00");
        assertThat(outlook.expectedIn()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("walk-in CASH invoices are left out")
    void walkInInvoicesAreLeftOut() {
        invoice("86.00", TODAY.plusDays(2), TODAY, CASH_PARTY);

        CashOutlookResponse outlook = service.outlook(30);

        assertThat(outlook.expectedIn()).isEqualByComparingTo("0.00");
        assertThat(outlook.overdueIn().count()).isZero();
    }

    @Test
    @DisplayName("an invoice with nothing open is left out")
    void settledInvoiceIsLeftOut() {
        ExtInvoice invoice = invoice("100.00", TODAY.plusDays(2), TODAY.minusDays(5), CUSTOMER);
        invoiceBalances.put(invoice.getInvoiceId(), BigDecimal.ZERO);

        assertThat(service.outlook(30).expectedIn()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("an invoice due beyond the horizon is neither in the line nor overdue")
    void invoiceBeyondTheHorizon() {
        invoice("100.00", TODAY.plusDays(40), TODAY.minusDays(5), CUSTOMER);

        CashOutlookResponse outlook = service.outlook(30);

        assertThat(outlook.expectedIn()).isEqualByComparingTo("0.00");
        assertThat(outlook.overdueIn().count()).isZero();
    }

    // ---- low point and cushion -------------------------------------------------------------------------------

    @Test
    @DisplayName("AC 7: a cushion of 5,000.00 and a low point of 4,200.00 is below the cushion; the float is not in it")
    void belowTheCushion() {
        approved("6800.00", TODAY.plusDays(3), TODAY.minusDays(30));
        invoice("3000.00", TODAY.plusDays(10), TODAY.minusDays(30), CUSTOMER);
        when(settings.cashSafetyCushion()).thenReturn(Optional.of(new BigDecimal("5000.00")));

        CashOutlookResponse outlook = service.outlook(30);

        assertThat(outlook.lowPoint().balance()).isEqualByComparingTo("4200.00");
        assertThat(outlook.lowPoint().date()).isEqualTo(TODAY.plusDays(3));
        assertThat(outlook.safetyCushion()).isEqualByComparingTo("5000.00");
        assertThat(outlook.belowCushion()).isTrue();
        assertThat(outlook.startingBalance()).as("drawers are not in the start").isEqualByComparingTo("11000.00");
    }

    @Test
    @DisplayName("a low point equal to the cushion is not below it")
    void equalToTheCushionIsNotBelow() {
        approved("6800.00", TODAY.plusDays(3), TODAY.minusDays(30));
        when(settings.cashSafetyCushion()).thenReturn(Optional.of(new BigDecimal("4200.00")));

        assertThat(service.outlook(30).belowCushion()).isFalse();
    }

    @Test
    @DisplayName("a cushion of zero is a real value: a negative low point is below it")
    void zeroCushionIsReal() {
        approved("12000.00", TODAY.plusDays(3), TODAY.minusDays(30));
        when(settings.cashSafetyCushion()).thenReturn(Optional.of(BigDecimal.ZERO));

        CashOutlookResponse outlook = service.outlook(30);

        assertThat(outlook.lowPoint().balance()).isEqualByComparingTo("-1000.00");
        assertThat(outlook.safetyCushion()).isEqualByComparingTo("0.00");
        assertThat(outlook.belowCushion()).isTrue();
    }

    @Test
    @DisplayName("with no cushion set, safetyCushion is null and belowCushion false, however low the balance")
    void cushionUnset() {
        approved("12000.00", TODAY.plusDays(3), TODAY.minusDays(30));

        CashOutlookResponse outlook = service.outlook(30);

        assertThat(outlook.safetyCushion()).isNull();
        assertThat(outlook.belowCushion()).isFalse();
    }

    @Test
    @DisplayName("the low point is the lowest day, the first of equals")
    void lowPointIsTheLowestDay() {
        approved("1000.00", TODAY.plusDays(2), TODAY.minusDays(30));
        invoice("400.00", TODAY.plusDays(6), TODAY.minusDays(30), CUSTOMER);
        approved("100.00", TODAY.plusDays(8), TODAY.minusDays(30));

        CashOutlookResponse outlook = service.outlook(30);

        assertThat(outlook.lowPoint().date()).isEqualTo(TODAY.plusDays(2));
        assertThat(outlook.lowPoint().balance()).isEqualByComparingTo("10000.00");
        assertThat(point(outlook, TODAY.plusDays(9)).balance()).isEqualByComparingTo("10300.00");
    }

    @Test
    @DisplayName("the position's one-read overload projects over the cash it is given")
    void overloadUsesTheGivenCash() {
        CashAccounts.Components cash = new CashAccounts.Components(
                List.of(row("1000", "500.00")), List.of(), List.of(), BigDecimal.ZERO, null, 0);

        CashOutlookResponse outlook = service.outlook(5, cash);

        assertThat(outlook.startingBalance()).isEqualByComparingTo("500.00");
        verify(cashAccounts, never()).read();
        verifyNoMoreInteractions(cashAccounts);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static CashAccounts.Row row(String code, String balance) {
        return new CashAccounts.Row(UUID.randomUUID(), code, "Account " + code, new BigDecimal(balance));
    }

    private static Point point(CashOutlookResponse outlook, LocalDate date) {
        return outlook.points().stream()
                .filter(point -> point.date().equals(date))
                .findFirst()
                .orElseThrow();
    }

    private static APPaymentAllocationRepository.VendorBillAllocationSum allocation(UUID billId, BigDecimal amount) {
        return new APPaymentAllocationRepository.VendorBillAllocationSum() {
            @Override
            public UUID getVendorBillId() {
                return billId;
            }

            @Override
            public BigDecimal getAllocated() {
                return amount;
            }
        };
    }

    private VendorBill approved(String total, LocalDate dueDate, LocalDate billDate) {
        return bill(VendorBillStatus.APPROVED, total, dueDate, billDate, "USD");
    }

    private VendorBill bill(
            VendorBillStatus status, String total, LocalDate dueDate, LocalDate billDate, String currency) {
        VendorBill bill = new VendorBill(UUID.randomUUID());
        bill.setVendorId(VENDOR);
        bill.setStatus(status);
        bill.setTotalAmount(new BigDecimal(total));
        bill.setCurrency(currency);
        bill.setBillDate(billDate.atTime(9, 30));
        bill.setDueDate(dueDate == null ? null : dueDate.atStartOfDay());
        bills.add(bill);
        return bill;
    }

    private void poTerms(String terms) {
        ExtOrderPurchaseOrder po = new ExtOrderPurchaseOrder();
        po.setPurchaseOrderId(PO);
        po.setPaymentTermsId(terms);
        when(purchaseOrders.findAllById(any())).thenReturn(List.of(po));
    }

    private ExtInvoice invoice(String balance, LocalDate dueDate, LocalDate finalized, UUID party) {
        ExtInvoice invoice = ExtInvoice.builder()
                .invoiceId(UUID.randomUUID())
                .invoiceNumber("INV-" + invoices.size())
                .status("FINALIZED")
                .partyId(party.toString())
                .total(new BigDecimal(balance))
                .dueDate(dueDate)
                .finalizedAt(finalized.atTime(10, 0).toInstant(ZoneOffset.UTC))
                .updatedAt(NOW)
                .build();
        invoices.add(invoice);
        invoiceBalances.put(invoice.getInvoiceId(), new BigDecimal(balance));
        return invoice;
    }

    private static ExtCustomerBillingRules rules(String terms) {
        return ExtCustomerBillingRules.builder().partyId(CUSTOMER).paymentTerms(terms).build();
    }
}
