package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillLine;
import com.positivity.accounting.internal.entity.VendorBillMatchEvidence;
import com.positivity.accounting.internal.enums.MatchConfidence;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchEvidenceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Compares a vendor's invoice with the receipt lines of a goods-receipt bill, keeps what the vendor billed and writes
 * the match evidence (CAP:550 S12, #2509; SPEC-accounting-workspace §4.3 "Matching keeps what was billed", P3; AW39).
 * {@code /match} and candidate selection share it, so both keep the same billed amounts and the same evidence.
 *
 * <p><b>Pairing.</b> When the invoice has as many lines as the receipt, line i pairs with line i (the tolerance
 * check's long-standing order); otherwise each invoice line pairs with the first unpaired receipt line of the same
 * product. A receipt line no invoice line pairs with is billed 0; an invoice line with no receipt line becomes a line
 * of its own with nothing received, which posts as {@code GOODS} (2100 at the billed net).
 *
 * <p><b>Tolerance.</b> Quantity within 0.1% and unit price within 5% of the received line, and the billed total
 * within 5% of the received total; an unpaired line on either side is outside it.
 *
 * <p><b>The received baseline.</b> Every comparison is against what was received: the lines with a received quantity
 * above 0 and the sum of their line totals, never the bill's current total or a line an earlier match added. A
 * second match first removes what the first kept, and {@link #restoreReceived} (resolve-exception {@code CORRECT})
 * puts the bill back to its receipt.
 */
@Component
@RequiredArgsConstructor
public class VendorBillInvoiceMatcher {

    static final BigDecimal QUANTITY_TOLERANCE_PERCENT = new BigDecimal("0.001");
    static final BigDecimal PRICE_TOLERANCE_PERCENT = new BigDecimal("0.05");

    private final Clock clock;
    private final VendorBillLineRepository billLines;
    private final VendorBillMatchEvidenceRepository evidence;
    private final LedgerCurrency ledgerCurrency;

    /** One line of the vendor's invoice, as billed. */
    public record InvoiceLine(
            @NonNull UUID productId,
            @Nullable String description,
            @NonNull BigDecimal quantity,
            @NonNull BigDecimal unitPrice) {

        BigDecimal net() {
            return quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
        }
    }

    /** The comparison the tolerance check made, line by line. */
    public record Comparison(
            @NonNull List<Map<String, Object>> lines,
            boolean withinTolerance,
            @NonNull BigDecimal receivedTotal,
            @NonNull BigDecimal billedTotal) {}

    /** The points of one scored candidate (P3). */
    public record Points(int amount, int products, int date, int purchaseOrder) {

        int total() {
            return amount + products + date + purchaseOrder;
        }
    }

    /** Compares {@code invoice} with the bill's receipt lines; writes nothing. */
    public @NonNull Comparison compare(@NonNull VendorBill bill, @NonNull List<InvoiceLine> invoice) {
        List<VendorBillLine> received =
                received(billLines.findByVendorBill_VendorBillIdOrderByLineNumber(bill.getVendorBillId()));
        return compare(received, invoice, pair(received, invoice));
    }

    /**
     * Keeps what the vendor billed (AW39): each receipt line's billed quantity and price, a line of its own for an
     * invoice line with no receipt behind it, and the bill's total as the billed total. What an earlier match kept is
     * replaced, never added to. Returns the comparison with the receipt.
     */
    public @NonNull Comparison applyBilled(@NonNull VendorBill bill, @NonNull List<InvoiceLine> invoice) {
        List<VendorBillLine> stored = billLines.findByVendorBill_VendorBillIdOrderByLineNumber(bill.getVendorBillId());
        List<VendorBillLine> received = received(stored);
        removeBilledOnly(stored, received);
        int[] pairing = pair(received, invoice);
        Comparison comparison = compare(received, invoice, pairing);
        boolean[] paired = new boolean[received.size()];
        int nextLineNumber =
                received.stream().mapToInt(VendorBillLine::getLineNumber).max().orElse(0) + 1;
        for (int i = 0; i < invoice.size(); i++) {
            InvoiceLine billed = invoice.get(i);
            if (pairing[i] >= 0) {
                VendorBillLine line = received.get(pairing[i]);
                paired[pairing[i]] = true;
                line.setBilledQuantity(billed.quantity());
                line.setBilledUnitPrice(billed.unitPrice());
                billLines.save(line);
            } else {
                VendorBillLine line = new VendorBillLine();
                line.setVendorBill(bill);
                line.setLineNumber(nextLineNumber++);
                line.setProductId(billed.productId());
                line.setDescription(billed.description());
                line.setQuantity(BigDecimal.ZERO);
                line.setUnitPrice(billed.unitPrice());
                line.setLineTotal(BigDecimal.ZERO);
                line.setInventoryItem(true);
                line.setBilledQuantity(billed.quantity());
                line.setBilledUnitPrice(billed.unitPrice());
                billLines.save(line);
            }
        }
        for (int r = 0; r < received.size(); r++) {
            if (!paired[r]) {
                VendorBillLine line = received.get(r);
                line.setBilledQuantity(BigDecimal.ZERO);
                line.setBilledUnitPrice(line.getUnitPrice());
                billLines.save(line);
            }
        }
        bill.setTotalAmount(comparison.billedTotal());
        return comparison;
    }

    /**
     * Puts a matched bill back to its receipt (resolve-exception {@code CORRECT}, #2509 review): the lines a match
     * added are removed, the billed quantity and price of every received line cleared, and the total is the received
     * total again. The match evidence stays: it is append-only.
     */
    public void restoreReceived(@NonNull VendorBill bill) {
        List<VendorBillLine> stored = billLines.findByVendorBill_VendorBillIdOrderByLineNumber(bill.getVendorBillId());
        List<VendorBillLine> received = received(stored);
        removeBilledOnly(stored, received);
        for (VendorBillLine line : received) {
            if (line.getBilledQuantity() != null || line.getBilledUnitPrice() != null) {
                line.setBilledQuantity(null);
                line.setBilledUnitPrice(null);
                billLines.save(line);
            }
        }
        if (!received.isEmpty()) {
            bill.setTotalAmount(receivedTotal(received));
        }
    }

    /** The lines a receipt put on the bill: a received quantity above 0. A line a match added has 0. */
    static @NonNull List<VendorBillLine> received(@NonNull List<VendorBillLine> stored) {
        return stored.stream()
                .filter(line -> line.getQuantity() != null && line.getQuantity().signum() > 0)
                .toList();
    }

    /** The received total: the sum of the received lines' totals, to the cent. */
    static @NonNull BigDecimal receivedTotal(@NonNull List<VendorBillLine> stored) {
        return received(stored).stream()
                .map(line -> line.getLineTotal() != null
                        ? line.getLineTotal()
                        : line.getQuantity().multiply(line.getUnitPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Whether a match kept what an invoice billed on the bill's lines (AW44): a billed quantity on any line. */
    static boolean billed(@NonNull List<VendorBillLine> stored) {
        return stored.stream().anyMatch(line -> line.getBilledQuantity() != null);
    }

    private void removeBilledOnly(List<VendorBillLine> stored, List<VendorBillLine> received) {
        List<VendorBillLine> billedOnly =
                stored.stream().filter(line -> !received.contains(line)).toList();
        if (!billedOnly.isEmpty()) {
            billLines.deleteAll(billedOnly);
            billLines.flush();
        }
    }

    /** Writes the append-only evidence of one match of {@code bill} (#2509). */
    public @NonNull VendorBillMatchEvidence record(
            @NonNull VendorBill bill,
            @NonNull UUID invoiceEventId,
            VendorBillMatchEvidence.@NonNull Source source,
            @NonNull MatchConfidence confidence,
            @NonNull Points points,
            @NonNull String invoiceReference,
            @NonNull LocalDateTime invoiceDate,
            @NonNull LocalDateTime receivedDate,
            @NonNull Comparison comparison,
            @NonNull String recordedBy) {
        VendorBillMatchEvidence row = new VendorBillMatchEvidence();
        row.setVendorBillId(bill.getVendorBillId());
        row.setInvoiceEventId(invoiceEventId);
        row.setSource(source);
        row.setConfidence(confidence);
        row.setScore(points.total());
        row.setAmountPoints(points.amount());
        row.setProductPoints(points.products());
        row.setDatePoints(points.date());
        row.setPurchaseOrderPoints(points.purchaseOrder());
        row.setInvoiceReference(invoiceReference);
        row.setInvoiceDate(invoiceDate);
        row.setReceivedDate(receivedDate);
        row.setReceivedTotal(comparison.receivedTotal());
        row.setBilledTotal(comparison.billedTotal());
        row.setCurrencyCode(currencyOf(bill));
        row.setWithinTolerance(comparison.withinTolerance());
        row.setLineComparison(comparison.lines());
        row.setRecordedBy(recordedBy);
        row.setRecordedAt(Instant.now(clock));
        return evidence.save(row);
    }

    /** The invoice lines as a candidate row keeps them (JSON), and back. */
    public static @NonNull List<Map<String, Object>> toJson(@NonNull List<InvoiceLine> invoice) {
        List<Map<String, Object>> json = new ArrayList<>();
        for (InvoiceLine line : invoice) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("productId", line.productId().toString());
            entry.put("description", line.description());
            entry.put("quantity", line.quantity().toPlainString());
            entry.put("unitPrice", line.unitPrice().toPlainString());
            json.add(entry);
        }
        return json;
    }

    public static @NonNull List<InvoiceLine> fromJson(@Nullable List<Map<String, Object>> json) {
        List<InvoiceLine> lines = new ArrayList<>();
        if (json == null) {
            return lines;
        }
        for (Map<String, Object> entry : json) {
            Object description = entry.get("description");
            lines.add(new InvoiceLine(
                    UUID.fromString(String.valueOf(entry.get("productId"))),
                    description == null ? null : String.valueOf(description),
                    new BigDecimal(String.valueOf(entry.get("quantity"))),
                    new BigDecimal(String.valueOf(entry.get("unitPrice")))));
        }
        return lines;
    }

    /** {@code pairing[i]} is the receipt line invoice line i pairs with, or -1. */
    static int @NonNull [] pair(@NonNull List<VendorBillLine> received, @NonNull List<InvoiceLine> invoice) {
        int[] pairing = new int[invoice.size()];
        if (received.size() == invoice.size()) {
            for (int i = 0; i < invoice.size(); i++) {
                pairing[i] = i;
            }
            return pairing;
        }
        boolean[] taken = new boolean[received.size()];
        for (int i = 0; i < invoice.size(); i++) {
            pairing[i] = -1;
            for (int r = 0; r < received.size(); r++) {
                if (!taken[r]
                        && received.get(r).getProductId().equals(invoice.get(i).productId())) {
                    taken[r] = true;
                    pairing[i] = r;
                    break;
                }
            }
        }
        return pairing;
    }

    private static Comparison compare(List<VendorBillLine> received, List<InvoiceLine> invoice, int[] pairing) {
        List<Map<String, Object>> lines = new ArrayList<>();
        boolean within = received.size() == invoice.size();
        boolean[] paired = new boolean[received.size()];
        BigDecimal billedTotal = BigDecimal.ZERO;
        for (int i = 0; i < invoice.size(); i++) {
            InvoiceLine billed = invoice.get(i);
            billedTotal = billedTotal.add(billed.net());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(VendorBillMatchEvidence.PRODUCT_ID, billed.productId().toString());
            entry.put(VendorBillMatchEvidence.BILLED_QUANTITY, billed.quantity().toPlainString());
            entry.put(
                    VendorBillMatchEvidence.BILLED_UNIT_PRICE,
                    billed.unitPrice().toPlainString());
            if (pairing[i] >= 0) {
                VendorBillLine line = received.get(pairing[i]);
                paired[pairing[i]] = true;
                boolean quantityOk = line.getQuantity()
                                .subtract(billed.quantity())
                                .abs()
                                .compareTo(line.getQuantity().multiply(QUANTITY_TOLERANCE_PERCENT))
                        <= 0;
                boolean priceOk = line.getUnitPrice()
                                .subtract(billed.unitPrice())
                                .abs()
                                .compareTo(line.getUnitPrice().multiply(PRICE_TOLERANCE_PERCENT))
                        <= 0;
                within &= quantityOk && priceOk;
                entry.put(VendorBillMatchEvidence.LINE_NUMBER, line.getLineNumber());
                entry.put(
                        VendorBillMatchEvidence.RECEIVED_QUANTITY,
                        line.getQuantity().toPlainString());
                entry.put(
                        VendorBillMatchEvidence.RECEIVED_UNIT_PRICE,
                        line.getUnitPrice().toPlainString());
                entry.put(VendorBillMatchEvidence.QUANTITY_WITHIN_TOLERANCE, quantityOk);
                entry.put(VendorBillMatchEvidence.PRICE_WITHIN_TOLERANCE, priceOk);
            } else {
                within = false;
                entry.put(VendorBillMatchEvidence.QUANTITY_WITHIN_TOLERANCE, false);
                entry.put(VendorBillMatchEvidence.PRICE_WITHIN_TOLERANCE, false);
            }
            lines.add(entry);
        }
        for (int r = 0; r < received.size(); r++) {
            if (!paired[r]) {
                VendorBillLine line = received.get(r);
                within = false;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put(VendorBillMatchEvidence.LINE_NUMBER, line.getLineNumber());
                entry.put(
                        VendorBillMatchEvidence.PRODUCT_ID, line.getProductId().toString());
                entry.put(
                        VendorBillMatchEvidence.RECEIVED_QUANTITY,
                        line.getQuantity().toPlainString());
                entry.put(
                        VendorBillMatchEvidence.RECEIVED_UNIT_PRICE,
                        line.getUnitPrice().toPlainString());
                entry.put(VendorBillMatchEvidence.BILLED_QUANTITY, "0");
                entry.put(VendorBillMatchEvidence.QUANTITY_WITHIN_TOLERANCE, false);
                entry.put(VendorBillMatchEvidence.PRICE_WITHIN_TOLERANCE, false);
                lines.add(entry);
            }
        }
        BigDecimal receivedTotal = receivedTotal(received);
        BigDecimal totalTolerance = receivedTotal.multiply(PRICE_TOLERANCE_PERCENT);
        within &= receivedTotal.subtract(billedTotal).abs().compareTo(totalTolerance) <= 0;
        return new Comparison(lines, within, receivedTotal, billedTotal);
    }

    private String currencyOf(VendorBill bill) {
        return bill.getCurrency() == null || bill.getCurrency().isBlank()
                ? ledgerCurrency.code()
                : bill.getCurrency().trim();
    }
}
