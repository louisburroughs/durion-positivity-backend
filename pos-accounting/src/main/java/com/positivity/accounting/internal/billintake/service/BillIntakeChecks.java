package com.positivity.accounting.internal.billintake.service;

import com.positivity.accounting.internal.billintake.entity.BillIntakeItem;
import com.positivity.accounting.internal.billintake.enums.BillFieldState;
import com.positivity.accounting.internal.billintake.enums.BillIntakeStatus;
import com.positivity.accounting.internal.billintake.enums.BillVendorResolution;
import com.positivity.accounting.internal.billintake.repository.BillIntakeItemRepository;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillNumbers;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.service.VendorBillDuplicateGuard;
import com.positivity.accounting.internal.service.VendorBillTotals;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The checks every draft gets when it becomes {@code READY_FOR_REVIEW} (CAP:550 S25, #2518): vendor resolution (ruling 3),
 * totals consistency (ruling 5) and the duplicate pre-check into {@code possibleDuplicates}. They only set field states
 * and the draft's resolution and warnings; they refuse nothing, and a person decides (confirmation re-checks the hard
 * duplicate rules). They read the draft's read-back, text and numbers only (ADR-0072), and log nothing about it.
 *
 * <h2>Vendor resolution (ruling 3)</h2>
 *
 * The read-back's {@code vendor} carries what was read: {@code readName}, {@code readNumber} and {@code
 * readRegistration} ({@code scheme}, {@code region}, {@code last4}). An exact normalised match of the name against a
 * copy vendor's display name or vendor number, or of the number against its vendor number, that yields one vendor is
 * {@code READ}; several are {@code CANDIDATES}. A registration whose scheme and last four match can only narrow a
 * name match or, alone, give {@code CANDIDATES}, never {@code READ}: four characters are not unique. No match is
 * {@code NOT_SET_UP}. The copy holds no full tax number and no legal name, so neither is compared.
 */
@Service
@RequiredArgsConstructor
public class BillIntakeChecks {

    static final int MAX_CANDIDATES = 20;

    private final ExtSupplierVendorRepository vendors;
    private final VendorBillDuplicateGuard duplicateGuard;
    private final BillIntakeItemRepository items;

    /** Runs the checks on {@code item} in the caller's transaction. */
    public void apply(@NonNull BillIntakeItem item) {
        Map<String, Object> readBack =
                item.getReadBack() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(item.getReadBack());
        resolveVendor(item, readBack);
        checkTotals(readBack);
        item.setReadBack(readBack);
        item.setPossibleDuplicates(duplicates(item, readBack));
    }

    // ---- vendor resolution (ruling 3) ------------------------------------------------------------------

    private void resolveVendor(BillIntakeItem item, Map<String, Object> readBack) {
        if (item.getVendorResolution() == BillVendorResolution.RESOLVED) {
            return;
        }
        Map<String, Object> vendor = copyOf(readBack.get("vendor"));
        String nameKey = key(text(vendor.get("readName")));
        String numberKey = key(text(vendor.get("readNumber")));
        Map<String, Object> registration = asMap(vendor.get("readRegistration"));
        List<ExtSupplierVendor> all = nameKey.isEmpty() && numberKey.isEmpty() && registration == null
                ? List.of()
                : vendors.findAll();

        List<ExtSupplierVendor> nameMatches = all.stream()
                .filter(v -> matchesName(v, nameKey, numberKey))
                .toList();
        List<ExtSupplierVendor> registrationMatches = registration == null
                ? List.of()
                : all.stream().filter(v -> matchesRegistration(v, registration)).toList();

        BillVendorResolution resolution;
        List<ExtSupplierVendor> candidates = List.of();
        ExtSupplierVendor chosen = null;
        if (nameMatches.size() == 1) {
            resolution = BillVendorResolution.READ;
            chosen = nameMatches.get(0);
        } else if (nameMatches.size() > 1) {
            resolution = BillVendorResolution.CANDIDATES;
            List<ExtSupplierVendor> narrowed =
                    nameMatches.stream().filter(registrationMatches::contains).toList();
            candidates = narrowed.isEmpty() ? nameMatches : narrowed;
        } else if (!registrationMatches.isEmpty()) {
            resolution = BillVendorResolution.CANDIDATES;
            candidates = registrationMatches;
        } else {
            resolution = BillVendorResolution.NOT_SET_UP;
        }

        item.setVendorResolution(resolution);
        readBack.remove("vendorCandidates");
        if (chosen != null) {
            vendor.put("value", vendorValue(chosen));
            vendor.put("state", BillFieldState.READ.name());
        } else {
            vendor.put("value", null);
            vendor.put("state", BillFieldState.CHECK_THIS.name());
            if (!candidates.isEmpty()) {
                readBack.put(
                        "vendorCandidates",
                        candidates.stream().limit(MAX_CANDIDATES).map(BillIntakeChecks::vendorValue).toList());
            }
        }
        readBack.put("vendor", vendor);
    }

    private static boolean matchesName(ExtSupplierVendor vendor, String nameKey, String numberKey) {
        String display = key(vendor.getDisplayName());
        String number = key(vendor.getVendorNumber());
        return (!nameKey.isEmpty() && (nameKey.equals(display) || nameKey.equals(number)))
                || (!numberKey.isEmpty() && numberKey.equals(number));
    }

    private static boolean matchesRegistration(ExtSupplierVendor vendor, Map<String, Object> read) {
        String scheme = text(read.get("scheme"));
        String region = text(read.get("region"));
        String last4 = text(read.get("last4"));
        if (scheme == null || last4 == null || vendor.getTaxRegistrations() == null) {
            return false;
        }
        return vendor.getTaxRegistrations().stream()
                .anyMatch(known -> scheme.equalsIgnoreCase(known.get("scheme"))
                        && last4.equalsIgnoreCase(known.get("last4"))
                        && (region == null || known.get("region") == null || region.equalsIgnoreCase(known.get("region"))));
    }

    private static Map<String, Object> vendorValue(ExtSupplierVendor vendor) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("vendorId", vendor.getVendorId().toString());
        value.put("displayName", vendor.getDisplayName());
        value.put("vendorNumber", vendor.getVendorNumber());
        return value;
    }

    private static String key(@Nullable String text) {
        return text == null || text.isBlank() ? "" : VendorBillNumbers.normalise(text.trim());
    }

    // ---- totals (ruling 5) -----------------------------------------------------------------------------

    private void checkTotals(Map<String, Object> readBack) {
        BigDecimal total = number(readBack.get("totalAmount"));
        BigDecimal tax = number(readBack.get("taxAmount"));
        BigDecimal freight = number(readBack.get("freight"));
        BigDecimal net = number(readBack.get("netAmount"));
        List<Map<String, Object>> lines = mapList(readBack.get("lines"));
        List<BigDecimal> lineAmounts = lines.stream()
                .map(line -> number(line.get("lineAmount")))
                .filter(Objects::nonNull)
                .toList();

        if (total != null) {
            BigDecimal expected = null;
            int statedLines = 1;
            if (!lines.isEmpty() && lineAmounts.size() == lines.size()) {
                expected = sum(lineAmounts).add(orZero(freight)).add(orZero(tax));
                statedLines = lines.size();
            } else if (net != null) {
                expected = net.add(orZero(tax));
            }
            if (expected != null
                    && expected.subtract(total).abs().compareTo(VendorBillTotals.tolerance(statedLines)) > 0) {
                needsChecking(readBack, "totalAmount");
            }
        }
        List<Map<String, Object>> taxByType = mapList(readBack.get("taxByType"));
        if (tax != null && !taxByType.isEmpty()) {
            List<BigDecimal> amounts = taxByType.stream()
                    .map(entry -> number(entry.get("amount")))
                    .filter(Objects::nonNull)
                    .toList();
            if (amounts.size() != taxByType.size()
                    || sum(amounts).subtract(tax).abs().compareTo(VendorBillTotals.tolerance(1)) > 0) {
                needsChecking(readBack, "taxAmount");
            }
        }
    }

    private static void needsChecking(Map<String, Object> readBack, String field) {
        Map<String, Object> value = copyOf(readBack.get(field));
        value.put("state", BillFieldState.CHECK_THIS.name());
        readBack.put(field, value);
    }

    // ---- possible duplicates ---------------------------------------------------------------------------

    private List<Map<String, Object>> duplicates(BillIntakeItem item, Map<String, Object> readBack) {
        List<Map<String, Object>> found = new ArrayList<>();
        UUID vendorId = vendorIdOf(readBack);
        String billNumber = fieldText(readBack, "billNumber");
        LocalDate billDate = date(fieldText(readBack, "billDate"));
        if (vendorId == null) {
            return found;
        }
        String vendorName = vendorNameOf(readBack);
        String purchaseOrder = fieldText(readBack, "purchaseOrderNumber");
        String delivery = fieldText(readBack, "deliveryReference");

        if (billNumber != null && billDate != null) {
            duplicateGuard
                    .findOriginal(vendorId, billNumber, billDate.atStartOfDay(), null)
                    .ifPresent(bill -> found.add(entry("BILL_NUMBER", bill)));
        }
        var byDelivery = duplicateGuard.findDeliveryOriginal(vendorId, purchaseOrder, delivery);
        byDelivery.ifPresent(bill -> found.add(entry("DELIVERY_REFERENCE", bill)));
        if (byDelivery.isEmpty()) {
            duplicateGuard.findPurchaseOrderMatches(vendorId, purchaseOrder).stream()
                    .findFirst()
                    .ifPresent(bill -> found.add(entry("PURCHASE_ORDER", bill)));
        }
        if (billNumber != null && billDate != null) {
            String numberKey = VendorBillNumbers.normalise(billNumber);
            for (BillIntakeItem other : items.findByStatus(BillIntakeStatus.READY_FOR_REVIEW)) {
                if (Objects.equals(other.getIntakeId(), item.getIntakeId()) || other.getReadBack() == null) {
                    continue;
                }
                String otherNumber = fieldText(other.getReadBack(), "billNumber");
                LocalDate otherDate = date(fieldText(other.getReadBack(), "billDate"));
                if (vendorId.equals(vendorIdOf(other.getReadBack()))
                        && otherNumber != null
                        && numberKey.equals(VendorBillNumbers.normalise(otherNumber))
                        && billDate.equals(otherDate)) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("matchedBy", "OPEN_DRAFT");
                    entry.put("billNumber", otherNumber);
                    entry.put("vendorName", vendorName);
                    entry.put("billDate", otherDate.toString());
                    entry.put("intakeReference", other.getIntakeReference());
                    entry.put("intakeId", other.getIntakeId() == null ? null : other.getIntakeId().toString());
                    found.add(entry);
                }
            }
        }
        return found;
    }

    private static Map<String, Object> entry(String matchedBy, VendorBill bill) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("matchedBy", matchedBy);
        entry.put("billNumber", bill.getBillNumber());
        entry.put("vendorName", bill.getVendorName());
        LocalDateTime billDate = bill.getBillDate();
        entry.put("billDate", billDate == null ? null : billDate.toLocalDate().toString());
        entry.put("billId", bill.getVendorBillId().toString());
        return entry;
    }

    // ---- read-back access ------------------------------------------------------------------------------

    private static @Nullable UUID vendorIdOf(Map<String, Object> readBack) {
        Map<String, Object> vendor = asMap(readBack.get("vendor"));
        Map<String, Object> value = vendor == null ? null : asMap(vendor.get("value"));
        String id = value == null ? null : text(value.get("vendorId"));
        try {
            return id == null ? null : UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static @Nullable String vendorNameOf(Map<String, Object> readBack) {
        Map<String, Object> vendor = asMap(readBack.get("vendor"));
        Map<String, Object> value = vendor == null ? null : asMap(vendor.get("value"));
        return value == null ? null : text(value.get("displayName"));
    }

    private static @Nullable String fieldText(Map<String, Object> readBack, String name) {
        Map<String, Object> field = asMap(readBack.get(name));
        return field == null ? null : text(field.get("value"));
    }

    private static @Nullable LocalDate date(@Nullable String text) {
        if (text == null || text.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable BigDecimal number(@Nullable Object field) {
        Map<String, Object> map = asMap(field);
        Object value = map == null ? null : map.get("value");
        if (value == null) {
            value = map == null ? null : map.get("amount");
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal sum(List<BigDecimal> amounts) {
        return amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal orZero(@Nullable BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private static @Nullable String text(@Nullable Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> asMap(@Nullable Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static Map<String, Object> copyOf(@Nullable Object value) {
        Map<String, Object> map = asMap(value);
        return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
    }

    private static List<Map<String, Object>> mapList(@Nullable Object value) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (value instanceof List<?> raw) {
            for (Object element : raw) {
                Map<String, Object> map = asMap(element);
                if (map != null) {
                    list.add(map);
                }
            }
        }
        return list;
    }
}
