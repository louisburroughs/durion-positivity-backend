package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.ExtOrderPurchaseOrder;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.TermsSource;
import com.positivity.accounting.internal.repository.ExtOrderPurchaseOrderRepository;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.service.EstimatedDueDateService.EstimatedDueDate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Estimated vendor-bill due date (#2515, AW11 amendment)")
class EstimatedDueDateServiceTest {

    private static final LocalDate BILL_DATE = LocalDate.of(2026, 10, 1);
    private static final UUID VENDOR = UUID.fromString("019a0000-0000-7000-8000-00000000c001");
    private static final UUID PO = UUID.fromString("019a0000-0000-7000-8000-00000000b001");

    private final ExtOrderPurchaseOrderRepository purchaseOrders = mock(ExtOrderPurchaseOrderRepository.class);
    private final ExtSupplierVendorRepository vendors = mock(ExtSupplierVendorRepository.class);
    private final CashAndPayablesSettings settings = mock(CashAndPayablesSettings.class);
    private EstimatedDueDateService service;

    @BeforeEach
    void setUp() {
        service = new EstimatedDueDateService(purchaseOrders, vendors, settings);
        when(settings.apDefaultTerms()).thenReturn("NET30");
    }

    private static VendorBill bill(UUID purchaseOrderId, LocalDateTime dueDate) {
        VendorBill bill = new VendorBill(UUID.randomUUID());
        bill.setVendorId(VENDOR);
        bill.setBillDate(BILL_DATE.atTime(14, 30));
        bill.setDueDate(dueDate);
        bill.setPurchaseOrderId(purchaseOrderId);
        return bill;
    }

    private void poTerms(String terms) {
        ExtOrderPurchaseOrder po = new ExtOrderPurchaseOrder();
        po.setPurchaseOrderId(PO);
        po.setPaymentTermsId(terms);
        when(purchaseOrders.findAllById(any())).thenReturn(List.of(po));
    }

    private void vendorTerms(String terms) {
        ExtSupplierVendor vendor = new ExtSupplierVendor();
        vendor.setVendorId(VENDOR);
        vendor.setDefaultPaymentTerms(terms);
        when(vendors.findAllById(any())).thenReturn(List.of(vendor));
    }

    @Test
    @DisplayName("a bill with a real due date has no estimate")
    void realDueDateHasNoEstimate() {
        assertThat(service.estimate(bill(null, BILL_DATE.plusDays(5).atStartOfDay())))
                .isEmpty();
    }

    @Test
    @DisplayName("the PO's terms win when they parse")
    void purchaseOrderTermsFirst() {
        poTerms("NET45");
        vendorTerms("NET10");
        VendorBill bill = bill(PO, null);

        Optional<EstimatedDueDate> estimate = service.estimate(bill);

        assertThat(estimate).contains(new EstimatedDueDate(BILL_DATE.plusDays(45), TermsSource.PURCHASE_ORDER));
        assertThat(bill.getDueDate())
                .as("the estimate is never written to the bill")
                .isNull();
    }

    @Test
    @DisplayName("an unparsable PO term falls through to the vendor's default terms")
    void unparsablePoFallsToVendor() {
        poTerms("2/10 NET 30");
        vendorTerms("Net 15");

        assertThat(service.estimate(bill(PO, null)))
                .contains(new EstimatedDueDate(BILL_DATE.plusDays(15), TermsSource.VENDOR));
    }

    @Test
    @DisplayName("a bill with no PO, a vendor without terms and no copy at all fall to the tenant default")
    void tenantDefaultLast() {
        vendorTerms(null);
        assertThat(service.estimate(bill(null, null)))
                .contains(new EstimatedDueDate(BILL_DATE.plusDays(30), TermsSource.TENANT_DEFAULT));

        when(vendors.findAllById(any())).thenReturn(List.of());
        when(settings.apDefaultTerms()).thenReturn("DUE_ON_RECEIPT");
        assertThat(service.estimate(bill(null, null)))
                .contains(new EstimatedDueDate(BILL_DATE, TermsSource.TENANT_DEFAULT));
    }

    @Test
    @DisplayName("DUE_ON_RECEIPT on the PO is zero days")
    void dueOnReceiptIsZeroDays() {
        poTerms("Due on Receipt");

        assertThat(service.estimate(bill(PO, null)))
                .contains(new EstimatedDueDate(BILL_DATE, TermsSource.PURCHASE_ORDER));
    }

    @ParameterizedTest
    @CsvSource({
        "NET30,30",
        "Net 45,45",
        "NET 7,7",
        "net60,60",
        "NET1,1",
        "NET120,120",
        "DUE_ON_RECEIPT,0",
        "Due on Receipt,0",
        "due_on_receipt,0",
        " NET30 ,30"
    })
    @DisplayName("accepted terms forms")
    void parses(String terms, int days) {
        assertThat(CashAndPayablesSettings.termsDays(terms)).isEqualTo(OptionalInt.of(days));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " ",
                "NET0",
                "NET121",
                "NET",
                "NET-5",
                "NET_30",
                "NET  30",
                "2/10 NET 30",
                "soon",
                "30",
                "NET030"
            })
    @DisplayName("anything else does not parse")
    void doesNotParse(String terms) {
        assertThat(CashAndPayablesSettings.termsDays(terms)).isEmpty();
    }

    @Test
    @DisplayName("estimateAll loads the PO copies and vendor copies once each, whatever the bill count")
    void batchLoadsOnce() {
        poTerms("NET20");
        vendorTerms("NET10");
        VendorBill onPo = bill(PO, null);
        VendorBill noPo = bill(null, null);
        VendorBill dated = bill(PO, BILL_DATE.plusDays(3).atStartOfDay());

        Map<VendorBill, EstimatedDueDate> estimates = service.estimateAll(List.of(onPo, noPo, dated));

        assertThat(estimates).hasSize(2);
        assertThat(estimates.get(onPo).termsSource()).isEqualTo(TermsSource.PURCHASE_ORDER);
        assertThat(estimates.get(noPo)).isEqualTo(new EstimatedDueDate(BILL_DATE.plusDays(10), TermsSource.VENDOR));
        verify(purchaseOrders, times(1)).findAllById(any());
        verify(vendors, times(1)).findAllById(any());
    }
}
