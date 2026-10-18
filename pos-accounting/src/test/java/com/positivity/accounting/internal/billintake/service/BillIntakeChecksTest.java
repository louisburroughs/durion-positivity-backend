package com.positivity.accounting.internal.billintake.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.billintake.entity.BillIntakeItem;
import com.positivity.accounting.internal.billintake.enums.BillIntakeStatus;
import com.positivity.accounting.internal.billintake.enums.BillVendorResolution;
import com.positivity.accounting.internal.billintake.repository.BillIntakeItemRepository;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.service.VendorBillDuplicateGuard;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Vendor resolution (ruling 3), totals (ruling 5) and the duplicate pre-check of a new draft (S25 #2518). */
@DisplayName("BillIntakeChecks (S25 #2518)")
class BillIntakeChecksTest {

    private final ExtSupplierVendorRepository vendors = mock(ExtSupplierVendorRepository.class);
    private final VendorBillDuplicateGuard guard = mock(VendorBillDuplicateGuard.class);
    private final BillIntakeItemRepository items = mock(BillIntakeItemRepository.class);
    private final BillIntakeChecks checks = new BillIntakeChecks(vendors, guard, items);

    private static ExtSupplierVendor vendor(String number, String name, Map<String, String>... registrations) {
        ExtSupplierVendor vendor = new ExtSupplierVendor();
        vendor.setVendorId(UUID.randomUUID());
        vendor.setVendorNumber(number);
        vendor.setDisplayName(name);
        vendor.setStatus(ExtSupplierVendor.ACTIVE);
        vendor.setTaxRegistrations(List.of(registrations));
        return vendor;
    }

    private static Map<String, Object> field(Object value, String state) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("value", value);
        field.put("state", state);
        return field;
    }

    private static BillIntakeItem draftReading(String vendorName, String vendorNumber, Map<String, String> registration) {
        BillIntakeItem item = new BillIntakeItem();
        item.setIntakeId(UUID.randomUUID());
        Map<String, Object> vendor = field(null, "CHECK_THIS");
        vendor.put("readName", vendorName);
        vendor.put("readNumber", vendorNumber);
        vendor.put("readRegistration", registration);
        Map<String, Object> readBack = new LinkedHashMap<>();
        readBack.put("vendor", vendor);
        item.setReadBack(readBack);
        return item;
    }

    @BeforeEach
    void noDuplicates() {
        when(guard.findOriginal(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(guard.findDeliveryOriginal(any(), any(), any())).thenReturn(Optional.empty());
        when(guard.findPurchaseOrderMatches(any(), any())).thenReturn(List.of());
        when(items.findByStatus(BillIntakeStatus.READY_FOR_REVIEW)).thenReturn(List.of());
    }

    // ---- ruling 3 ----

    @Test
    @DisplayName("one exact normalised name match is READ, with the vendor in the field")
    void oneNameMatchIsRead() {
        ExtSupplierVendor acme = vendor("V-1", "Acme Parts Co.");
        when(vendors.findAll()).thenReturn(List.of(acme, vendor("V-2", "Other Supplier")));
        BillIntakeItem item = draftReading("ACME  parts co", null, null);

        checks.apply(item);

        assertThat(item.getVendorResolution()).isEqualTo(BillVendorResolution.READ);
        assertThat(item.getReadBack().get("vendor").toString())
                .contains("READ")
                .contains(acme.getVendorId().toString());
    }

    @Test
    @DisplayName("a vendor number read from the document matches the copy's vendor number")
    void numberMatch() {
        ExtSupplierVendor acme = vendor("V-000123", "Acme");
        when(vendors.findAll()).thenReturn(List.of(acme));

        BillIntakeItem item = draftReading(null, "v000123", null);
        checks.apply(item);

        assertThat(item.getVendorResolution()).isEqualTo(BillVendorResolution.READ);
    }

    @Test
    @DisplayName("several vendors with the same normalised name are CANDIDATES, CHECK_THIS, never READ")
    void severalAreCandidates() {
        when(vendors.findAll()).thenReturn(List.of(vendor("V-1", "Acme"), vendor("V-2", "ACME.")));
        BillIntakeItem item = draftReading("acme", null, null);

        checks.apply(item);

        assertThat(item.getVendorResolution()).isEqualTo(BillVendorResolution.CANDIDATES);
        assertThat(item.getReadBack().get("vendor").toString()).contains("CHECK_THIS");
        assertThat((List<?>) item.getReadBack().get("vendorCandidates")).hasSize(2);
    }

    @Test
    @DisplayName("a registration (scheme and last four) alone is CANDIDATES, never READ; it narrows several name matches")
    void registrationOnlyNarrows() {
        Map<String, String> reg = Map.of("scheme", "VAT", "region", "DE", "last4", "1234");
        ExtSupplierVendor holder = vendor("V-1", "Alpha", reg);
        when(vendors.findAll()).thenReturn(List.of(holder, vendor("V-2", "Beta")));

        BillIntakeItem alone = draftReading(null, null, Map.of("scheme", "VAT", "region", "DE", "last4", "1234"));
        checks.apply(alone);
        assertThat(alone.getVendorResolution()).isEqualTo(BillVendorResolution.CANDIDATES);
        assertThat((List<?>) alone.getReadBack().get("vendorCandidates")).hasSize(1);

        ExtSupplierVendor twin = vendor("V-3", "Alpha");
        when(vendors.findAll()).thenReturn(List.of(holder, twin));
        BillIntakeItem narrowed = draftReading("Alpha", null, Map.of("scheme", "VAT", "region", "DE", "last4", "1234"));
        checks.apply(narrowed);
        assertThat(narrowed.getVendorResolution()).isEqualTo(BillVendorResolution.CANDIDATES);
        assertThat((List<?>) narrowed.getReadBack().get("vendorCandidates")).hasSize(1);
    }

    @Test
    @DisplayName("no match is NOT_SET_UP; a person's pick (RESOLVED) is never overwritten")
    void noMatchAndResolved() {
        when(vendors.findAll()).thenReturn(List.of(vendor("V-1", "Acme")));
        BillIntakeItem none = draftReading("Nobody Ltd", null, null);
        checks.apply(none);
        assertThat(none.getVendorResolution()).isEqualTo(BillVendorResolution.NOT_SET_UP);

        BillIntakeItem picked = draftReading("Nobody Ltd", null, null);
        picked.setVendorResolution(BillVendorResolution.RESOLVED);
        checks.apply(picked);
        assertThat(picked.getVendorResolution()).isEqualTo(BillVendorResolution.RESOLVED);
    }

    // ---- ruling 5 ----

    private static BillIntakeItem withAmounts(Object net, Object tax, Object freight, Object total, List<Object> lines) {
        BillIntakeItem item = new BillIntakeItem();
        item.setIntakeId(UUID.randomUUID());
        item.setVendorResolution(BillVendorResolution.NOT_SET_UP);
        Map<String, Object> readBack = new LinkedHashMap<>();
        if (net != null) {
            readBack.put("netAmount", field(net, "READ"));
        }
        if (tax != null) {
            readBack.put("taxAmount", field(tax, "READ"));
        }
        if (freight != null) {
            readBack.put("freight", field(freight, "READ"));
        }
        readBack.put("totalAmount", field(total, "READ"));
        if (lines != null) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Object amount : lines) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("lineAmount", field(amount, "READ"));
                list.add(line);
            }
            readBack.put("lines", list);
        }
        item.setReadBack(readBack);
        return item;
    }

    private static String stateOf(BillIntakeItem item, String name) {
        return ((Map<?, ?>) item.getReadBack().get(name)).get("state").toString();
    }

    @Test
    @DisplayName("lines plus freight plus tax must equal the total, else the total is CHECK_THIS")
    void linesFreightTax() {
        BillIntakeItem ok = withAmounts(null, "8.00", "5.00", "113.00", List.of("60.00", "40.00"));
        checks.apply(ok);
        assertThat(stateOf(ok, "totalAmount")).isEqualTo("READ");

        BillIntakeItem off = withAmounts(null, "8.00", "5.00", "120.00", List.of("60.00", "40.00"));
        checks.apply(off);
        assertThat(stateOf(off, "totalAmount")).isEqualTo("CHECK_THIS");
    }

    @Test
    @DisplayName("with no lines, net plus tax must equal the total")
    void netPlusTax() {
        BillIntakeItem ok = withAmounts("100.00", "8.00", null, "108.00", null);
        checks.apply(ok);
        assertThat(stateOf(ok, "totalAmount")).isEqualTo("READ");

        BillIntakeItem off = withAmounts("100.00", "8.00", null, "109.00", null);
        checks.apply(off);
        assertThat(stateOf(off, "totalAmount")).isEqualTo("CHECK_THIS");
    }

    @Test
    @DisplayName("tax by type must add up to the tax, else the tax is CHECK_THIS")
    void taxByType() {
        BillIntakeItem item = withAmounts("100.00", "8.00", null, "108.00", null);
        item.getReadBack().put(
                "taxByType",
                List.of(Map.of("taxType", "VAT", "amount", "5.00"), Map.of("taxType", "LEVY", "amount", "2.00")));

        checks.apply(item);

        assertThat(stateOf(item, "taxAmount")).isEqualTo("CHECK_THIS");
        assertThat(stateOf(item, "totalAmount")).isEqualTo("READ");
    }

    // ---- duplicates ----

    private BillIntakeItem resolvedDraft(ExtSupplierVendor acme) {
        BillIntakeItem item = new BillIntakeItem();
        item.setIntakeId(UUID.randomUUID());
        item.setVendorResolution(BillVendorResolution.RESOLVED);
        Map<String, Object> vendor = field(
                Map.of("vendorId", acme.getVendorId().toString(), "displayName", "Acme", "vendorNumber", "V-1"), "ACCEPTED");
        Map<String, Object> readBack = new LinkedHashMap<>();
        readBack.put("vendor", vendor);
        readBack.put("billNumber", field("INV-77", "READ"));
        readBack.put("billDate", field("2026-10-01", "READ"));
        readBack.put("purchaseOrderNumber", field("PO-42", "READ"));
        readBack.put("deliveryReference", field("DN-1", "READ"));
        item.setReadBack(readBack);
        return item;
    }

    private static VendorBill bill(String number) {
        VendorBill bill = new VendorBill(UUID.randomUUID());
        bill.setBillNumber(number);
        bill.setVendorName("Acme");
        bill.setBillDate(LocalDateTime.of(2026, 10, 1, 0, 0));
        return bill;
    }

    @Test
    @DisplayName("the number rule, the delivery reference and an open draft with the same vendor, number and date are all listed")
    void listsEveryKindOfDuplicate() {
        ExtSupplierVendor acme = vendor("V-1", "Acme");
        BillIntakeItem item = resolvedDraft(acme);
        BillIntakeItem other = resolvedDraft(acme);
        other.setIntakeReference("BI-202610-0000009");
        when(guard.findOriginal(any(), any(), any(), any())).thenReturn(Optional.of(bill("INV-77")));
        when(guard.findDeliveryOriginal(acme.getVendorId(), "PO-42", "DN-1")).thenReturn(Optional.of(bill("GR-1")));
        when(items.findByStatus(BillIntakeStatus.READY_FOR_REVIEW)).thenReturn(List.of(item, other));

        checks.apply(item);

        assertThat(item.getPossibleDuplicates())
                .extracting(entry -> entry.get("matchedBy"))
                .containsExactly("BILL_NUMBER", "DELIVERY_REFERENCE", "OPEN_DRAFT");
        assertThat(item.getPossibleDuplicates().get(2)).containsEntry("intakeReference", "BI-202610-0000009");
    }

    @Test
    @DisplayName("an order only is a warning against the order's goods-receipt bills, not a delivery match")
    void purchaseOrderOnly() {
        ExtSupplierVendor acme = vendor("V-1", "Acme");
        BillIntakeItem item = resolvedDraft(acme);
        when(guard.findPurchaseOrderMatches(acme.getVendorId(), "PO-42")).thenReturn(List.of(bill("GR-1")));

        checks.apply(item);

        assertThat(item.getPossibleDuplicates())
                .singleElement()
                .satisfies(entry -> assertThat(entry).containsEntry("matchedBy", "PURCHASE_ORDER"));
    }

    @Test
    @DisplayName("with no resolved vendor there is nothing to compare: no duplicates are listed")
    void noVendorNoDuplicates() {
        BillIntakeItem item = withAmounts(null, null, null, "10.00", null);

        checks.apply(item);

        assertThat(item.getPossibleDuplicates()).isEmpty();
    }
}
