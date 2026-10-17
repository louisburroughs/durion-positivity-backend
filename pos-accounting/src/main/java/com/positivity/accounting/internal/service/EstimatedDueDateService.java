package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ExtOrderPurchaseOrder;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.TermsSource;
import com.positivity.accounting.internal.repository.ExtOrderPurchaseOrderRepository;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Estimates the due date of a vendor bill that has none (#2515, CAP:550 S19; AW11 as amended 2026-10-07).
 *
 * <p>A bill with a real {@code dueDate} has no estimate. Otherwise the terms come, in order, from the purchase order's
 * {@code paymentTermsId} (pos-accounting's {@link ExtOrderPurchaseOrder} copy), the vendor's {@code defaultPaymentTerms}
 * ({@link ExtSupplierVendor}), and the tenant's {@code AP_DEFAULT_TERMS}; a value that does not parse falls through to
 * the next. The estimate is the bill date plus the terms' days, tagged with the {@link TermsSource} that gave them.
 *
 * <p>The estimate is a read-side figure: it is never written to the bill, and the bill's {@code dueDate} stays null.
 *
 * <p>Terms accepted: {@code NET<n>}, {@code Net <n>} and {@code NET <n>} (n from 1 to 120, any case, optional single
 * space) read as n days; {@code DUE_ON_RECEIPT} and {@code Due on Receipt} (any case) as 0. Anything else does not
 * parse.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EstimatedDueDateService {

    private static final Pattern NET = Pattern.compile("(?i)net ?([0-9]{1,3})");
    private static final Pattern DUE_ON_RECEIPT = Pattern.compile("(?i)due[_ ]on[_ ]receipt");

    /** An estimated due date and the terms source behind it. */
    public record EstimatedDueDate(@NonNull LocalDate dueDate, @NonNull TermsSource termsSource) {}

    private final ExtOrderPurchaseOrderRepository purchaseOrders;
    private final ExtSupplierVendorRepository vendors;
    private final CashAndPayablesSettings settings;

    /** The bill's estimated due date; empty when it has a real due date. */
    public @NonNull Optional<EstimatedDueDate> estimate(@NonNull VendorBill bill) {
        return Optional.ofNullable(estimateAll(List.of(bill)).get(bill));
    }

    /**
     * The estimates of many bills, keyed by bill (identity of the entity, as the caller holds it); a bill with a real
     * due date is absent. Loads the purchase-order copies and the vendor copies in one query each, and the tenant
     * default once.
     */
    public @NonNull Map<VendorBill, EstimatedDueDate> estimateAll(@NonNull Collection<VendorBill> bills) {
        Map<VendorBill, EstimatedDueDate> estimates = new IdentityHashMap<>();
        Set<UUID> poIds = new HashSet<>();
        Set<UUID> vendorIds = new HashSet<>();
        for (VendorBill bill : bills) {
            if (bill.getDueDate() != null) {
                continue;
            }
            if (bill.getPurchaseOrderId() != null) {
                poIds.add(bill.getPurchaseOrderId());
            }
            vendorIds.add(bill.getVendorId());
        }
        if (vendorIds.isEmpty()) {
            return estimates;
        }
        Map<UUID, String> poTerms = new HashMap<>();
        if (!poIds.isEmpty()) {
            for (ExtOrderPurchaseOrder po : purchaseOrders.findAllById(poIds)) {
                poTerms.put(po.getPurchaseOrderId(), po.getPaymentTermsId());
            }
        }
        Map<UUID, String> vendorTerms = new HashMap<>();
        for (ExtSupplierVendor vendor : vendors.findAllById(vendorIds)) {
            vendorTerms.put(vendor.getVendorId(), vendor.getDefaultPaymentTerms());
        }
        String tenantDefault = settings.apDefaultTerms();
        for (VendorBill bill : bills) {
            if (bill.getDueDate() != null) {
                continue;
            }
            Objects.requireNonNull(bill.getBillDate(), "bill date");
            LocalDate billDate = bill.getBillDate().toLocalDate();
            OptionalInt days = parseDays(bill.getPurchaseOrderId() == null ? null : poTerms.get(bill.getPurchaseOrderId()));
            TermsSource source = TermsSource.PURCHASE_ORDER;
            if (days.isEmpty()) {
                days = parseDays(vendorTerms.get(bill.getVendorId()));
                source = TermsSource.VENDOR;
            }
            if (days.isEmpty()) {
                days = parseDays(tenantDefault);
                source = TermsSource.TENANT_DEFAULT;
            }
            // The tenant default is validated on read and never null, so it always parses; the fallback is belt only.
            int n = days.orElse(30);
            estimates.put(bill, new EstimatedDueDate(billDate.plusDays(n), source));
        }
        return estimates;
    }

    /** The days a terms text stands for; empty when it is null, blank or not in the accepted forms. */
    static @NonNull OptionalInt parseDays(@Nullable String terms) {
        if (terms == null) {
            return OptionalInt.empty();
        }
        String value = terms.trim();
        if (DUE_ON_RECEIPT.matcher(value).matches()) {
            return OptionalInt.of(0);
        }
        Matcher net = NET.matcher(value);
        if (net.matches()) {
            int days = Integer.parseInt(net.group(1));
            if (days >= 1 && days <= CashAndPayablesSettings.MAX_NET_DAYS) {
                return OptionalInt.of(days);
            }
        }
        return OptionalInt.empty();
    }
}
