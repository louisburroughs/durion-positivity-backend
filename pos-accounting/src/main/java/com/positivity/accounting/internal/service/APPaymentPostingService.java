package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.EventOutbox;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException;
import com.positivity.accounting.internal.exception.GLAccountNotActiveException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.EventOutboxRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts an AP payment's own entry (CAP:550 S42, #2603; AW40, AW41), reading the {@code ap_payment} row, never the
 * event that announced it.
 *
 * <p><b>The entry</b>, dated the payment's {@code payment_date} (the business date fixed before the gateway call), every
 * mapped account through the {@value APPaymentPreGatewayChecks#POSTING_CATEGORY} posting category ({@link
 * GLMappingResolver}); no posting-rule version is involved:
 *
 * <ul>
 *   <li>Dr {@code ACCOUNTS_PAYABLE} (2000) the gross, applied and unapplied alike;
 *   <li>Dr {@code PAYMENT_FEES} (6030) the fee, when above zero;
 *   <li>Cr the payment's own bank account (never a mapping) the gross + fee.
 * </ul>
 *
 * Allocations post nothing, at payment or later: an unapplied amount stays a debit in 2000 for the vendor.
 *
 * <p><b>Once.</b> The payment row is locked first, so the outbox delivery and a {@code gl-posting-retry} serialize;
 * a payment already {@code GL_POSTED} posts nothing. The entry's source event derives from the posting key {@code
 * AP_PAYMENT:<paymentId>} ({@link JournalEntrySourceTypes#AP_PAYMENT}): an entry already posted under it is linked,
 * never posted again.
 *
 * <p><b>Refusals</b> propagate unwrapped: {@link GLMappingNotConfiguredException}, {@link GLAccountNotActiveException},
 * {@link AccountingPeriodClosedException}, {@link AccountingPeriodHardLockedException} and {@link
 * AccountingTimeZoneUnsetException} ({@link #refusalCode}). The transaction rolls back; the caller records the code on
 * the payment in a transaction of its own ({@link APPaymentFailurePersistenceService#persistGLPostRefusal}).
 *
 * <p><b>The period override.</b> A closed-period override the payer gave on the pay command is stored on the payment
 * and applied by the <em>outbox delivery only</em> ({@link #postPending}), as the payer ({@link
 * JournalEntryService#postJournalEntryWithRecordedOverride}): the override audit row names the payer (ruling 5 of
 * #2603). A {@code gl-posting-retry} is a new act (ruling 1 of 2026-10-08): it never uses the stored override, only the
 * caller's own {@code overrideJustification} with the caller's {@code accounting:period:override}, and it never changes
 * the stored override fields.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class APPaymentPostingService {

    /** Prefix of a payment's posting key, and the namespace of its entry's source event. */
    public static final String POSTING_KEY_PREFIX = "AP_PAYMENT:";

    private static final int DESCRIPTION_MAX = 500;

    private final Clock clock;
    private final APPaymentRepository payments;
    private final APPaymentPreGatewayChecks mappings;
    private final JournalEntryService journalEntryService;
    private final JournalEntryRepository journalEntryRepository;
    private final ApLockTimeout lockTimeout;
    private final EventOutboxRepository outbox;
    private final AccountingPeriodGate periodGate;
    private final AccountingCalendarZoneResolver zoneResolver;

    /** {@code posting_date_rule} of an entry dated the retry's business date because the payment date is hard-locked. */
    public static final String DEFERRED_PAST_HARD_LOCK = "DEFERRED_PAST_HARD_LOCK";

    /**
     * The outbox delivery: posts a {@code GL_POST_PENDING} payment. A payment already posted, or left {@code
     * GL_POST_FAILED} for {@code gl-posting-retry}, posts nothing.
     *
     * @return the posted entry's id, or {@code null} when nothing was posted
     */
    @Transactional
    public @Nullable UUID postPending(@NonNull UUID paymentId) {
        lockTimeout.apply();
        APPayment payment = payments.lockById(paymentId)
                .orElseThrow(() -> new IllegalStateException("AP payment " + paymentId + " not found for posting"));
        if (payment.getStatus() != APPaymentStatus.GL_POST_PENDING) {
            log.info(
                    "AP payment not pending posting, nothing posted | paymentId={} | status={}",
                    paymentId,
                    payment.getStatus());
            return null;
        }
        return post(payment, null, true);
    }

    /**
     * {@code POST /v1/accounting/ap/payments/{paymentId}/gl-posting-retry}: posts a {@code GL_POST_FAILED} payment on
     * its stored {@code payment_date}.
     *
     * @param overrideJustification the caller's closed-period justification, honoured with the caller's {@code
     *                              accounting:period:override}; the override stored by the pay command never applies
     * @return the posted entry's id
     * @throws EntityNotFoundException when no such payment is visible (404 {@code NOT_FOUND})
     * @throws VendorBillException {@code AP_PAYMENT_NOT_RETRYABLE} when the payment is not {@code GL_POST_FAILED}
     */
    @Transactional
    public @NonNull UUID retry(@NonNull UUID paymentId, @Nullable String overrideJustification) {
        return retry(paymentId, overrideJustification, false);
    }

    /**
     * {@link #retry(UUID, String)} with the #2729 remedies: a {@code GL_POST_PENDING} payment whose outbox row is
     * {@code FAILED} (its retries exhausted while the refusal could not be recorded) is retryable too, and {@code
     * deferPastHardLock} dates the entry on the retry's business date when the payment's own date is hard-locked
     * (AW42: never back into the original period); the payment then records {@value #DEFERRED_PAST_HARD_LOCK}.
     */
    @Transactional
    public @NonNull UUID retry(
            @NonNull UUID paymentId, @Nullable String overrideJustification, boolean deferPastHardLock) {
        lockTimeout.apply();
        APPayment payment =
                payments.lockById(paymentId).orElseThrow(() -> new EntityNotFoundException("AP payment not found"));
        boolean failedOutbox = payment.getStatus() == APPaymentStatus.GL_POST_PENDING
                && outbox.countByAggregateIdAndStatus(paymentId, EventOutbox.OutboxStatus.FAILED) > 0
                && outbox.countByAggregateIdAndStatus(paymentId, EventOutbox.OutboxStatus.PENDING) == 0;
        if (payment.getStatus() != APPaymentStatus.GL_POST_FAILED && !failedOutbox) {
            String remedy =
                    switch (payment.getStatus()) {
                        case GATEWAY_PENDING -> "; reconcile it with the gateway first (reconcileApPaymentGateway)";
                        case GL_POST_PENDING -> "; its outbox delivery is still live, wait for it";
                        default -> "";
                    };
            throw new VendorBillException(
                    VendorBillException.Code.AP_PAYMENT_NOT_RETRYABLE,
                    "AP payment " + payment.getPaymentRef() + " is " + payment.getStatus()
                            + "; only a payment whose posting failed (GL_POST_FAILED, or GL_POST_PENDING with its"
                            + " outbox row FAILED) can be posted again" + remedy);
        }
        return post(payment, overrideJustification, false, deferPastHardLock);
    }

    /**
     * The code a refused posting records on the payment, or empty when {@code failure} is not a refusal (a transient
     * failure the outbox retries).
     */
    public static @NonNull Optional<String> refusalCode(@NonNull Throwable failure) {
        return switch (failure) {
            case GLMappingNotConfiguredException _ -> Optional.of("GL_MAPPING_NOT_CONFIGURED");
            case AccountingPeriodClosedException _ -> Optional.of("PERIOD_CLOSED");
            case AccountingPeriodHardLockedException _ -> Optional.of("PERIOD_HARD_LOCKED");
            case AccountingTimeZoneUnsetException _ -> Optional.of("ACCOUNTING_TIME_ZONE_UNSET");
            // The payment's own bank account not active at the start of payment_date: configuration, not transient.
            case GLAccountNotActiveException _ -> Optional.of("GL_ACCOUNT_NOT_ACTIVE");
            default -> Optional.empty();
        };
    }

    /** A payment's posting key, {@code AP_PAYMENT:<paymentId>}. */
    public static @NonNull String postingKey(@NonNull UUID paymentId) {
        return POSTING_KEY_PREFIX + paymentId;
    }

    /** The source event of a payment's entry, derived from its posting key. */
    public static @NonNull UUID toSourceEventId(@NonNull UUID paymentId) {
        return UUID.nameUUIDFromBytes(postingKey(paymentId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param callerOverride      the retry caller's own justification (null on the outbox delivery)
     * @param applyRecordedOverride true on the outbox delivery only: the payer's stored override applies
     */
    private UUID post(APPayment payment, @Nullable String callerOverride, boolean applyRecordedOverride) {
        return post(payment, callerOverride, applyRecordedOverride, false);
    }

    private UUID post(
            APPayment payment,
            @Nullable String callerOverride,
            boolean applyRecordedOverride,
            boolean deferPastHardLock) {
        UUID paymentId = payment.getPaymentId();
        if (payment.getPaymentMethod() == PaymentMethod.CASH) {
            // A drawer cash-on-delivery payment (S17, #2513; AW41 ruling 2): its session-close entry
            // (Dr 2000 / Cr 1095) is its only posting, and it is created GL_POSTED and linked to it. The pay command
            // refuses CASH (APPaymentPreGatewayChecks), so reaching here without that entry is a defect, not a no-op.
            UUID closeEntry = payment.getGlJournalEntryId();
            if (closeEntry == null) {
                throw new IllegalStateException("CASH AP payment " + paymentId
                        + " has no session-close entry; a cash payment is created GL_POSTED by the close posting only");
            }
            log.info("CASH AP payment posts nothing of its own | paymentId={} | closeEntry={}", paymentId, closeEntry);
            return closeEntry;
        }
        UUID sourceEventId = toSourceEventId(paymentId);
        Optional<JournalEntry> alreadyPosted = journalEntryRepository.findBySourceEvent(sourceEventId).stream()
                .filter(entry -> entry.getStatus() == JournalEntryStatus.POSTED)
                .findFirst();
        if (alreadyPosted.isPresent()) {
            // The durable backstop: an entry under this payment's key is the payment's entry.
            UUID entryId = alreadyPosted.get().getJournalEntryId();
            markPosted(payment, entryId);
            log.info("AP payment entry already posted, linked | paymentId={} | journalEntryId={}", paymentId, entryId);
            return entryId;
        }
        if (payment.getPaymentDate() == null || payment.getBankAccountId() == null) {
            // Internal invariant: the pay command fixes both before the gateway call.
            throw new IllegalStateException(
                    "AP payment " + paymentId + " has no execution date or bank account; it cannot be posted");
        }

        LocalDateTime transactionDate = payment.getPaymentDate().atStartOfDay();
        String label =
                payment.getPaymentRef() + (payment.getVendorName() == null ? "" : " to " + payment.getVendorName());
        boolean deferred = false;
        if (deferPastHardLock && periodGate.isHardLocked(payment.getPaymentDate())) {
            // #2729 (AW42): the cash left the bank on payment_date, but that period is final. The entry is dated the
            // retry's business date, says where it came from, and the payment records the rule; the locked period
            // keeps the payment as a reconciling item.
            LocalDate today = zoneResolver.today();
            transactionDate = today.atStartOfDay();
            label = label + " (paid " + payment.getPaymentDate() + "; posted after the hard lock)";
            deferred = true;
        }
        BigDecimal gross = payment.getGrossAmount();
        BigDecimal fee = payment.getFeeAmount() == null ? BigDecimal.ZERO : payment.getFeeAmount();

        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines = new ArrayList<>();
        lines.add(line(
                mappings.resolve(APPaymentPreGatewayChecks.ACCOUNTS_PAYABLE_KEY, transactionDate),
                gross,
                BigDecimal.ZERO,
                "AP payment " + label));
        if (fee.signum() > 0) {
            lines.add(line(
                    mappings.resolve(APPaymentPreGatewayChecks.PAYMENT_FEES_KEY, transactionDate),
                    fee,
                    BigDecimal.ZERO,
                    "Bank fee on AP payment " + label));
        }
        lines.add(line(payment.getBankAccountId(), BigDecimal.ZERO, gross.add(fee), "Paid from bank: " + label));

        JournalEntryResponse created = journalEntryService.createJournalEntry(JournalEntryCreateRequest.builder()
                .transactionDate(transactionDate)
                .sourceEventId(sourceEventId)
                .sourceEventType(JournalEntrySourceTypes.AP_PAYMENT)
                .description(truncate("AP payment " + label, DESCRIPTION_MAX))
                .lines(lines)
                .build());
        UUID entryId = created.getJournalEntryId();
        JournalEntryResponse posted;
        if (applyRecordedOverride
                && payment.getPeriodOverrideJustification() != null
                && payment.getPeriodOverrideBy() != null) {
            posted = journalEntryService.postJournalEntryWithRecordedOverride(
                    entryId, payment.getPeriodOverrideJustification(), payment.getPeriodOverrideBy());
        } else {
            // A retry: the caller's own override (or none), checked against the caller's authority by the gate.
            posted = journalEntryService.postJournalEntry(entryId, callerOverride);
        }
        if (deferred) {
            payment.setPostingDateRule(DEFERRED_PAST_HARD_LOCK);
        }
        markPosted(payment, posted.getJournalEntryId());
        log.info(
                "AP payment posted | paymentId={} | paymentDate={} | postedOn={} | journalEntryId={}",
                paymentId,
                payment.getPaymentDate(),
                transactionDate.toLocalDate(),
                posted.getJournalEntryId());
        return posted.getJournalEntryId();
    }

    private void markPosted(APPayment payment, UUID journalEntryId) {
        payment.setGlJournalEntry(journalEntryRepository.getReferenceById(journalEntryId));
        payment.setGlPostedAt(Instant.now(clock));
        payment.setGlPostError(null);
        payment.setStatus(APPaymentStatus.GL_POSTED);
        payments.save(payment);
    }

    private static JournalEntryCreateRequest.JournalEntryLineRequest line(
            UUID account, BigDecimal debit, BigDecimal credit, String description) {
        return JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                .glAccountId(account)
                .debitAmount(debit)
                .creditAmount(credit)
                .description(truncate(description, DESCRIPTION_MAX))
                .build();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
