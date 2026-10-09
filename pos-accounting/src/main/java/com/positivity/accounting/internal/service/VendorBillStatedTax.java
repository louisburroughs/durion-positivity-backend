package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.bankrec.service.FunctionalCurrency;
import com.positivity.accounting.internal.dto.VendorBillCommands;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillTax;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.VendorBillTaxRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Stores the tax a vendor bill's document states, by tax type, from whichever channel the bill came through (CAP:550
 * S32d item 10; closes G11): the EDI fact's taxes (S23), the goods-receipt match request, or the {@code taxByType[]} a
 * person copies from the document when approving or accepting the bill (AW51). Amounts are kept as stated, signed like
 * the bill's total, and never recalculated. Every tenant's bills store them; only a recovery-enabled tenant's posting
 * uses them ({@link VendorBillTaxSplit}).
 */
@Component
@RequiredArgsConstructor
public class VendorBillStatedTax {

    private static final String FIELD = "taxByType";

    private final VendorBillTaxRepository billTaxes;
    private final FunctionalCurrency functionalCurrency;
    private final Clock clock;

    /**
     * Stores the document's tax by type on a bill just created from it; amounts signed like the bill. Nothing is
     * stored for a document without any. Repeated tax types are added together, so the stored rows stay one per type.
     */
    public void storeFromDocument(@NonNull VendorBill bill, @Nullable Map<String, BigDecimal> signedByType) {
        if (signedByType == null || signedByType.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        List<VendorBillTax> rows = new ArrayList<>();
        signedByType.forEach((taxType, amount) -> rows.add(
                VendorBillTax.of(bill.getVendorBillId(), taxType, amount, VendorBillTax.Source.DOCUMENT, now)));
        billTaxes.saveAll(rows);
    }

    /**
     * Replaces the bill's tax by type with the {@code taxByType[]} the approver copied from the document (AW51), after
     * checking it; does nothing when none was given.
     *
     * @throws VendorBillException 400 {@code VALIDATION_ERROR} for a tax type named twice; 422 {@code
     *     AP_BILL_TAX_SPLIT_MISMATCH} when the amounts do not add up to the bill's stated tax
     * @throws RuntimeException 422 {@code AMOUNT_PRECISION_EXCEEDS_CURRENCY} for an amount finer than the currency's
     *     minor unit (ADR-0067 PC-6), naming each one; never rounded
     */
    public void replaceFromApproval(@NonNull VendorBill bill, @Nullable List<VendorBillCommands.TaxAmount> taxByType) {
        if (taxByType == null) {
            return;
        }
        List<VendorBillException.FieldError> duplicates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        for (int i = 0; i < taxByType.size(); i++) {
            VendorBillCommands.TaxAmount entry = taxByType.get(i);
            if (!seen.add(entry.taxType())) {
                duplicates.add(new VendorBillException.FieldError(
                        FIELD + "[" + i + "].taxType", "names a tax type already given; give each type once"));
            }
            amounts.put(FIELD + "[" + i + "].amount", entry.amount());
        }
        if (!duplicates.isEmpty()) {
            throw new VendorBillException(
                    VendorBillException.Code.VALIDATION_ERROR,
                    "taxByType names a tax type more than once on bill " + bill.getBillNumber(),
                    duplicates,
                    null);
        }
        functionalCurrency.requireMinorUnits(amounts);

        BigDecimal stated = scaled(bill.getTaxAmount()).abs();
        BigDecimal given =
                taxByType.stream().map(VendorBillCommands.TaxAmount::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (given.compareTo(stated) != 0) {
            throw new VendorBillException(
                    VendorBillException.Code.AP_BILL_TAX_SPLIT_MISMATCH,
                    "The tax by type adds up to " + scaled(given).toPlainString() + " but bill "
                            + bill.getBillNumber() + " states tax of " + stated.toPlainString()
                            + "; copy each tax type from the vendor's document",
                    List.of(new VendorBillException.FieldError(
                            FIELD,
                            "adds up to " + scaled(given).toPlainString() + ", not the stated tax "
                                    + stated.toPlainString())),
                    "Copy the tax by type from the vendor's document so it adds up to the stated tax, or correct the"
                            + " bill");
        }
        BigDecimal sign = scaled(bill.getTotalAmount()).signum() < 0 ? BigDecimal.ONE.negate() : BigDecimal.ONE;
        billTaxes.deleteByVendorBillId(bill.getVendorBillId());
        Instant now = Instant.now(clock);
        List<VendorBillTax> rows = new ArrayList<>();
        for (VendorBillCommands.TaxAmount entry : taxByType) {
            rows.add(VendorBillTax.of(
                    bill.getVendorBillId(),
                    entry.taxType(),
                    entry.amount().multiply(sign),
                    VendorBillTax.Source.APPROVAL,
                    now));
        }
        billTaxes.saveAll(rows);
    }

    /**
     * The approval audit's record of the tax by type it copied: {@code taxByType=<type>:<amount>,...}; null when none
     * was given.
     */
    static @Nullable String auditOf(@Nullable List<VendorBillCommands.TaxAmount> taxByType) {
        if (taxByType == null) {
            return null;
        }
        StringBuilder text = new StringBuilder("taxByType=");
        for (int i = 0; i < taxByType.size(); i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(taxByType.get(i).taxType())
                    .append(':')
                    .append(taxByType.get(i).amount().toPlainString());
        }
        return text.toString();
    }

    private static BigDecimal scaled(@Nullable BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO.setScale(2) : amount.setScale(2, RoundingMode.HALF_UP);
    }
}
