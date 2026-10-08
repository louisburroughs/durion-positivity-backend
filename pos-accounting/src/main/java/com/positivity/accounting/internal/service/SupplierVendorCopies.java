package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ApVendorSettings;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.ApVendorSettingsRepository;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The vendor rules that read accounting's copy of the pos-supplier vendor master (CAP:550 S24, #2517;
 * SPEC-accounting-workspace §4.9; AW23). Neither this module nor this class calls pos-supplier (ADR-0044 R1): a vendor
 * is what the copy says it is.
 *
 * <ul>
 *   <li>New business ({@link #requireForNewBusiness}): a goods-receipt bill or an AP payment names a vendor in the copy
 *       (422 {@code VENDOR_NOT_FOUND}) that is {@code ACTIVE} (422 {@code VENDOR_INACTIVE}).
 *   <li>The remit-to stamp ({@link #remitToVersion}): the copy's current version when a bill is approved.
 *   <li>The remit-to check at payment ({@link #requireRemitToUnchanged}, rule 6).
 *   <li>The vendor creator's first bill ({@link #isCreatorsFirstBill}, rule 9).
 *   <li>The vendor's AP defaults ({@link #apDefaults}, rule 10; AW39).
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupplierVendorCopies {

    private final ExtSupplierVendorRepository vendors;
    private final ApVendorSettingsRepository settings;
    private final VendorBillRepository bills;

    /** The vendor as the copy holds it, in either status. */
    public @NonNull Optional<ExtSupplierVendor> find(@NonNull UUID vendorId) {
        return vendors.findById(vendorId);
    }

    /**
     * The vendor a new goods-receipt bill or AP payment names: in the copy, else 422 {@code VENDOR_NOT_FOUND}, and
     * {@code ACTIVE}, else 422 {@code VENDOR_INACTIVE} (AW23; ruling 3: an inactive vendor's existing bills are not paid
     * either).
     *
     * @param what what is refused, in business words ("A bill", "A payment")
     */
    public @NonNull ExtSupplierVendor requireForNewBusiness(@NonNull UUID vendorId, @NonNull String what) {
        ExtSupplierVendor vendor = vendors.findById(vendorId)
                .orElseThrow(() -> new VendorBillException(
                        VendorBillException.Code.VENDOR_NOT_FOUND,
                        what + " cannot name vendor " + vendorId + ": it is not in the vendor copy. Set the vendor up"
                                + " in pos-supplier (or wait for its copy to arrive), then try again"));
        if (!vendor.isActive()) {
            throw new VendorBillException(
                    VendorBillException.Code.VENDOR_INACTIVE,
                    what + " cannot name vendor " + vendor.getVendorNumber() + ": the vendor is inactive and takes no"
                            + " new bills or payments until pos-supplier reactivates it");
        }
        return vendor;
    }

    /**
     * The remit-to version a bill approved now records: the copy's current version (0 when the vendor has no
     * remit-to), or null when the vendor is not in the copy, which payment refuses until confirmed.
     */
    public @Nullable Integer remitToVersion(@NonNull UUID vendorId) {
        return vendors.findById(vendorId)
                .map(ExtSupplierVendor::getRemitToVersion)
                .orElse(null);
    }

    /**
     * Rule 6, the remit-to check at payment, on the planned and locked bills (ruling 4): a bill passes when it was
     * approved at the vendor's current remit-to version, or when someone other than {@code payer} confirmed the current
     * version. Otherwise the whole payment is refused, 409 {@code VENDOR_PAYMENT_DETAILS_CHANGED}, naming every such bill
     * by its number and the vendor by its number; a bill approved at no version (before S24) is one of them.
     *
     * @param planned the bills the payment would pay, all of {@code vendor}
     */
    public void requireRemitToUnchanged(
            @NonNull List<VendorBill> planned, @NonNull ExtSupplierVendor vendor, @NonNull String payer) {
        int current = vendor.getRemitToVersion();
        Optional<ApVendorSettings> confirmation = settings.findByVendorId(vendor.getVendorId());
        boolean confirmedByAnother = confirmation
                .filter(s -> Objects.equals(s.getConfirmedRemitToVersion(), current))
                .filter(s -> !payer.equals(s.getRemitToConfirmedBy()))
                .isPresent();
        if (confirmedByAnother) {
            return;
        }
        List<VendorBill> changed = planned.stream()
                .filter(bill -> !Objects.equals(bill.getApprovedRemitToVersion(), current))
                .toList();
        if (changed.isEmpty()) {
            return;
        }
        List<VendorBillException.FieldError> named = changed.stream()
                .map(bill -> new VendorBillException.FieldError(
                        bill.getBillNumber(),
                        "approved at remit-to version "
                                + (bill.getApprovedRemitToVersion() == null
                                        ? "none"
                                        : bill.getApprovedRemitToVersion().toString())
                                + "; vendor " + vendor.getVendorNumber() + " is now at version " + current))
                .toList();
        log.info(
                "AP payment refused: vendor {} remit-to changed since approval of bills {}",
                vendor.getVendorNumber(),
                changed.stream().map(VendorBill::getBillNumber).toList());
        throw new VendorBillException(
                VendorBillException.Code.VENDOR_PAYMENT_DETAILS_CHANGED,
                "Vendor " + vendor.getVendorNumber() + "'s payment details changed after bills "
                        + String.join(
                                ", ",
                                changed.stream().map(VendorBill::getBillNumber).toList())
                        + " were approved; nothing was paid",
                named,
                "Ask a holder of accounting:ap:approve other than the payer to confirm remit-to version " + current
                        + " (POST /v1/accounting/vendors/{vendorId}/remit-to-confirmation), then pay again");
    }

    /**
     * Rule 9 (ruling 2): whether {@code actor} created the vendor and no bill or credit note of it was ever approved, by
     * a person or the system, voided ones included. False for a vendor not in the copy.
     */
    public boolean isCreatorsFirstBill(@NonNull UUID vendorId, @Nullable String actor) {
        if (actor == null) {
            return false;
        }
        return vendors.findById(vendorId)
                        .filter(vendor -> actor.equals(vendor.getCreatedBy()))
                        .isPresent()
                && !bills.existsByVendorIdAndApprovedAtIsNotNull(vendorId);
    }

    /**
     * The vendor's AP defaults as a classification (rule 10, AW39): the default debit class and expense key, either
     * null; null when the vendor has none.
     */
    public VendorBillPostingService.@Nullable Classification apDefaults(@NonNull UUID vendorId) {
        return settings.findByVendorId(vendorId)
                .filter(s -> s.getDefaultDebitClass() != null || s.getDefaultExpenseMappingKey() != null)
                .map(s -> new VendorBillPostingService.Classification(
                        s.getDefaultDebitClass(), s.getDefaultExpenseMappingKey()))
                .orElse(null);
    }
}
