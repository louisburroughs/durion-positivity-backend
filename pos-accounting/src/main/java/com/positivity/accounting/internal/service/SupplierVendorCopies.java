package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ApVendorSettings;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.ApVendorSettingsRepository;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.web.common.ReplicationPendingException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The vendor rules that read accounting's copy of the pos-supplier vendor master (CAP:550 S24, #2517;
 * SPEC-accounting-workspace §4.9; AW23). Neither this module nor this class calls pos-supplier (ADR-0044 R1): a vendor
 * is what the copy says it is.
 *
 * <ul>
 *   <li>New business ({@link #requireForNewBusiness}): a goods-receipt bill or an AP payment names a vendor in the copy
 *       (503 {@code VENDOR_REPLICATION_PENDING} with {@code Retry-After} while it is not) that is {@code ACTIVE} (422
 *       {@code VENDOR_INACTIVE}).
 *   <li>The remit-to stamp ({@link #remitToVersion}): the copy's current version when a bill is approved.
 *   <li>The remit-to check at payment ({@link #requireRemitToUnchanged}, rule 6).
 *   <li>The vendor creator's first bill ({@link #isCreatorsFirstBill}, rule 9).
 *   <li>The vendor's AP defaults ({@link #apDefaults}, rule 10; AW39).
 *   <li>The AP payment hold (#2615): the check at payment ({@link #requireNotOnHold}, slot 1e) and the reads ({@link
 *       #apHold}, {@link #heldVendors}). The hold reason is CONFIDENTIAL (ADR-0072): no log line, message or metric tag
 *       here carries it.
 * </ul>
 */
@Slf4j
@Component
public class SupplierVendorCopies {

    /** 503: the vendor is not in the copy yet (ADR-0017 §1). */
    public static final String VENDOR_REPLICATION_PENDING = "VENDOR_REPLICATION_PENDING";

    /**
     * Counter of AP payments refused at the pay command, tagged with the refusal code (#2615). Today it counts only
     * {@code VENDOR_ON_AP_HOLD}; the other refusals of the pay command are not counted here.
     */
    public static final String PAYMENT_REFUSED_COUNTER = "accounting.ap_payment.refused";

    private final ExtSupplierVendorRepository vendors;
    private final ApVendorSettingsRepository settings;
    private final VendorBillRepository bills;
    private final @Nullable Counter refusedOnHold;

    public SupplierVendorCopies(
            ExtSupplierVendorRepository vendors,
            ApVendorSettingsRepository settings,
            VendorBillRepository bills,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.vendors = vendors;
        this.settings = settings;
        this.bills = bills;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.refusedOnHold = registry == null
                ? null
                : Counter.builder(PAYMENT_REFUSED_COUNTER)
                        .description("AP payments refused at the pay command, by refusal code")
                        .tag("code", VendorBillException.Code.VENDOR_ON_AP_HOLD.name())
                        .register(registry);
    }

    /**
     * A vendor's AP payment hold, as a bill read shows it.
     *
     * @param vendorNumber the vendor's number, null when the vendor is not in the copy
     * @param reason       why the vendor is held (CONFIDENTIAL, ADR-0072)
     * @param since        when the hold was set or its reason last changed
     */
    public record ApHold(
            @Nullable String vendorNumber,
            @NonNull String reason,
            @Nullable Instant since) {

        @Override
        public @NonNull String toString() {
            return "ApHold[vendorNumber=" + vendorNumber + ", since=" + since + "]";
        }
    }

    /** The vendor as the copy holds it, in either status. */
    public @NonNull Optional<ExtSupplierVendor> find(@NonNull UUID vendorId) {
        return vendors.findById(vendorId);
    }

    /**
     * The vendor a new goods-receipt bill or AP payment names: in the copy, else 503 {@code VENDOR_REPLICATION_PENDING}, and
     * {@code ACTIVE}, else 422 {@code VENDOR_INACTIVE} (AW23; ruling 3: an inactive vendor's existing bills are not paid
     * either).
     *
     * @param what what is refused, in business words ("A bill", "A payment")
     */
    public @NonNull ExtSupplierVendor requireForNewBusiness(@NonNull UUID vendorId, @NonNull String what) {
        // Absent from an event-fed copy is "not yet", never "no" (ADR-0017 §1): 503 with Retry-After (#1994 precedent).
        ExtSupplierVendor vendor = vendors.findById(vendorId).orElseThrow(() -> replicationPending(vendorId));
        if (!vendor.isActive()) {
            throw new VendorBillException(
                    VendorBillException.Code.VENDOR_INACTIVE,
                    what + " cannot name vendor " + vendor.getVendorNumber() + ": the vendor is inactive and takes no"
                            + " new bills or payments until pos-supplier reactivates it");
        }
        return vendor;
    }

    /**
     * Slot 1e of the pay command (#2615): a vendor on AP hold is refused with 422 {@code VENDOR_ON_AP_HOLD}, before the
     * plan, any payment row or the gateway. The settings row is read in the payment's transaction without a lock: the
     * hold is forward-looking, so a payment already past this check completes. The refusal is logged at INFO with the
     * payment reference, the vendor number and the code, and counted ({@value #PAYMENT_REFUSED_COUNTER}); the hold
     * reason is in none of them, nor in the message, which points to the vendor read instead (ADR-0072).
     *
     * @param vendor     the vendor slot 1d returned
     * @param paymentRef the payment's reference, for the log line
     */
    public void requireNotOnHold(@NonNull ExtSupplierVendor vendor, @Nullable String paymentRef) {
        boolean held = settings.findByVendorId(vendor.getVendorId())
                .map(ApVendorSettings::isApHold)
                .orElse(false);
        if (!held) {
            return;
        }
        log.info(
                "AP payment {} refused: vendor {} is on AP hold ({})",
                paymentRef,
                vendor.getVendorNumber(),
                VendorBillException.Code.VENDOR_ON_AP_HOLD);
        if (refusedOnHold != null) {
            refusedOnHold.increment();
        }
        throw new VendorBillException(
                VendorBillException.Code.VENDOR_ON_AP_HOLD,
                "Vendor " + vendor.getVendorNumber() + " is on AP hold; nothing was paid. The reason is on the vendor"
                        + " (GET /v1/accounting/vendors/" + vendor.getVendorId() + ")",
                List.of(),
                "Release the hold (PUT /v1/accounting/vendors/" + vendor.getVendorId() + "/ap-settings with"
                        + " apHold.onHold false) when the matter is settled, then pay again");
    }

    /**
     * The vendor's AP payment hold, when it is held (#2615): the bill read's {@code VENDOR_AP_HOLD} check.
     *
     * @param vendorId the vendor
     * @return the hold, or empty when the vendor is not held
     */
    public @NonNull Optional<ApHold> apHold(@NonNull UUID vendorId) {
        return settings.findByVendorId(vendorId)
                .filter(ApVendorSettings::isApHold)
                .map(row -> new ApHold(
                        vendors.findById(vendorId)
                                .map(ExtSupplierVendor::getVendorNumber)
                                .orElse(null),
                        row.getApHoldReason(),
                        row.getApHoldSetAt()));
    }

    /**
     * The held vendors among {@code vendorIds} and their hold reasons, in one settings query (#2615): a list page's
     * {@code vendorApHold} flags.
     *
     * @param vendorIds the page's vendors
     * @return vendor id to hold reason, held vendors only
     */
    public @NonNull Map<UUID, String> heldVendors(@NonNull Collection<UUID> vendorIds) {
        if (vendorIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> held = new LinkedHashMap<>();
        for (ApVendorSettings row : settings.findByVendorIdIn(vendorIds)) {
            if (row.isApHold()) {
                held.put(row.getVendorId(), row.getApHoldReason());
            }
        }
        return held;
    }

    /**
     * 503 {@code VENDOR_REPLICATION_PENDING} with {@code Retry-After} (ADR-0017 §1; pos-catalog's {@code
     * SKILL_REPLICATION_PENDING}, #1994): a vendor id the copy does not hold may only not have been copied yet, so its
     * absence is never answered as "no such vendor".
     */
    public static @NonNull ReplicationPendingException replicationPending(@NonNull UUID vendorId) {
        return new ReplicationPendingException(
                VENDOR_REPLICATION_PENDING,
                "The vendor is not in accounting's copy of the pos-supplier vendor master yet; set it up in pos-supplier"
                        + " or wait for its copy, then retry",
                vendorId);
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
     * Whether the vendor accepts tax charged on goods for resale (CAP:550 S43, AW44): its AP setting {@code
     * acceptTaxOnResaleGoods}, false without a settings row or a vendor.
     */
    public boolean acceptsTaxOnResaleGoods(@Nullable UUID vendorId) {
        return vendorId != null
                && settings.findByVendorId(vendorId)
                        .map(ApVendorSettings::isAcceptTaxOnResaleGoods)
                        .orElse(false);
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
