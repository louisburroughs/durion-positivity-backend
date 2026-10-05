package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillNumbers;
import com.positivity.accounting.internal.exception.VendorBillDuplicateException;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one duplicate rule for vendor bills, for every writer of a bill number (#2501; ADR-0070
 * Decision 4; SPEC-accounting-workspace §4.9).
 *
 * <p>Two bills are duplicates when they share the tenant, the vendor id as stored, the normalised
 * bill number ({@link VendorBillNumbers#normalise}) and the calendar date of the bill date, and
 * neither is {@code VOIDED} or {@code REJECTED}. The database is the authority: the partial unique
 * index {@value #INDEX_NAME} enforces the rule. This guard reads the same stored key ahead of the
 * write so the answer can name the original, and recognises the index's violation when a concurrent
 * writer wins the race between that read and the insert.
 */
@Slf4j
@Service
public class VendorBillDuplicateGuard {

    /** The partial unique index of {@code V4__vendor_bill_duplicate_rule.sql}. */
    public static final String INDEX_NAME = "uq_vendor_bill_duplicate_rule";

    static final String COUNTER_NAME = "accounting.vendor_bill.duplicate";

    /** The path a bill number arrived on; the counter's {@code channel} tag. */
    public enum Channel {
        GOODS_RECEIPT,
        MATCH,
        EDI
    }

    /** What the rule did with a duplicate; the counter's {@code outcome} tag. */
    public enum Outcome {
        REFUSED,
        FLAGGED,
        IGNORED,
        RETRIED
    }

    private final VendorBillRepository vendorBillRepository;
    private final @Nullable MeterRegistry meterRegistry;

    public VendorBillDuplicateGuard(
            VendorBillRepository vendorBillRepository, ObjectProvider<MeterRegistry> meterRegistry) {
        this.vendorBillRepository = vendorBillRepository;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    /**
     * The live bill that a bill with this vendor, number and date would duplicate.
     *
     * @param vendorId      the vendor id as it would be stored on the bill
     * @param billNumber    the bill number as written, not yet normalised
     * @param billDate      the bill date; only its calendar date counts
     * @param excludeBillId a bill that is not its own duplicate (the bill being renamed), or null
     * @return the live original, if there is one
     */
    public @NonNull Optional<VendorBill> findOriginal(
            @NonNull UUID vendorId,
            @NonNull String billNumber,
            @NonNull LocalDateTime billDate,
            @Nullable UUID excludeBillId) {
        LocalDate day = billDate.toLocalDate();
        return vendorBillRepository.findLiveDuplicate(
                vendorId,
                VendorBillNumbers.normalise(billNumber),
                day.atStartOfDay(),
                day.plusDays(1).atStartOfDay(),
                excludeBillId);
    }

    /**
     * {@link #findOriginal} in a read-only transaction of its own, for a writer whose insert has just
     * broken {@value #INDEX_NAME}. Postgres resolves a unique check only after the competing
     * transaction has committed, so the original is visible to a new transaction; the writer's own
     * transaction is aborted and can read nothing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public @NonNull Optional<VendorBill> findOriginalAfterCollision(
            @NonNull UUID vendorId, @NonNull String billNumber, @NonNull LocalDateTime billDate) {
        return findOriginal(vendorId, billNumber, billDate, null);
    }

    /**
     * Refuses a bill that would duplicate a live one.
     *
     * @param channel       the path the bill number arrived on
     * @param vendorId      the vendor id as it would be stored on the bill
     * @param billNumber    the bill number as written, not yet normalised
     * @param billDate      the bill date; only its calendar date counts
     * @param excludeBillId a bill that is not its own duplicate (the bill being renamed), or null
     * @throws VendorBillDuplicateException carrying the original, when there is one
     */
    public void refuseIfDuplicate(
            @NonNull Channel channel,
            @NonNull UUID vendorId,
            @NonNull String billNumber,
            @NonNull LocalDateTime billDate,
            @Nullable UUID excludeBillId) {
        Optional<VendorBill> original = findOriginal(vendorId, billNumber, billDate, excludeBillId);
        if (original.isPresent()) {
            throw refusal(channel, vendorId, billNumber, billDate, original.get());
        }
    }

    /**
     * Records a refusal and builds its exception; for the pre-check above and for a writer that lost
     * the race and read the original afterwards.
     */
    public @NonNull VendorBillDuplicateException refusal(
            @NonNull Channel channel,
            @NonNull UUID vendorId,
            @NonNull String billNumber,
            @NonNull LocalDateTime billDate,
            @NonNull VendorBill original) {
        record(channel, Outcome.REFUSED, vendorId, billNumber, billDate, original.getVendorBillId());
        return new VendorBillDuplicateException(original);
    }

    /**
     * One WARN line and one {@value #COUNTER_NAME} increment per refusal, flag, ignored duplicate or
     * retry. Ids, the key and the date only: no vendor name and no amount.
     *
     * @param originalBillId the live original, or null when it has not been read yet (a retry)
     */
    public void record(
            @NonNull Channel channel,
            @NonNull Outcome outcome,
            @NonNull UUID vendorId,
            @NonNull String billNumber,
            @NonNull LocalDateTime billDate,
            @Nullable UUID originalBillId) {
        String channelTag = channel.name().toLowerCase(Locale.ROOT);
        String outcomeTag = outcome.name().toLowerCase(Locale.ROOT);
        log.warn(
                "Vendor bill duplicate | channel={} | outcome={} | vendorId={} | key={} | billDate={} | originalBillId={}",
                channelTag,
                outcomeTag,
                vendorId,
                VendorBillNumbers.normalise(billNumber),
                billDate.toLocalDate(),
                originalBillId);
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(COUNTER_NAME)
                .description("Vendor bills that met the duplicate rule (vendor, normalised number, bill date)")
                .tag("channel", channelTag)
                .tag("outcome", outcomeTag)
                .register(meterRegistry)
                .increment();
    }

    /**
     * Whether a failure is the database refusing a row under {@value #INDEX_NAME}, anywhere in the
     * cause chain. Every other integrity violation (a column too long, another constraint) is not.
     */
    public static boolean isDuplicateRuleViolation(@Nullable Throwable failure) {
        int depth = 0;
        for (Throwable cause = failure; cause != null && depth < 16; cause = cause.getCause(), depth++) {
            String message = cause.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(INDEX_NAME)) {
                return true;
            }
        }
        return false;
    }
}
