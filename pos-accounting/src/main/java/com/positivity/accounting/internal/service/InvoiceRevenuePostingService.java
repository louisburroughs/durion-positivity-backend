package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.OutboxEventWriter;
import com.positivity.accounting.internal.entity.InvoiceGlPosting;
import com.positivity.accounting.internal.repository.InvoiceGlPostingRepository;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.accounting.InvoiceGlPostedV1;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts invoice revenue recognition to the GL from consumed {@code invoice.invoice.updated} facts
 * (issue #1843, ADR-0044 R6) — the entry that was missing between the {@code ext_invoice} replica
 * and the income statement, which sums posted journal-entry lines and therefore reported $0
 * revenue.
 *
 * <p><b>Entry:</b> a {@code FINALIZED} (or {@code POSTED}, for manifest replays of invoices the
 * old simulated path had already marked posted) invoice posts {@code Dr Accounts Receivable
 * (total) / Cr Service Revenue (total - tax) / Cr Sales Tax Payable (tax)}. pos-invoice computes
 * {@code total = subtotal + adjustments + tax}, so {@code total - tax} is exactly the revenue
 * portion and the entry balances by construction; a zero tax leg is omitted.
 *
 * <p><b>Reversal:</b> a {@code DRAFT} or {@code CANCELLED} fact for an invoice with an open
 * posting posts the mirror entry ({@code Dr Service Revenue / Dr Sales Tax Payable / Cr Accounts
 * Receivable}) dated at the revert's business time — the current open period, like the
 * credit-memo void mirror — and closes the posting row. Without an open posting it is a no-op.
 *
 * <p>Accounts are never hardcoded: all three legs resolve through the {@code INVOICE_REVENUE}
 * posting category and its {@code ACCOUNTS_RECEIVABLE} / {@code SERVICE_REVENUE} /
 * {@code SALES_TAX_PAYABLE} mapping keys (seeded by {@code R__seed_reference_accounting.sql}).
 *
 * <p><b>Idempotency</b> is the {@code invoice_gl_posting} row, written in the same transaction
 * as the journal entry: a redelivered or replayed fact finds the open row (or the already-reversed
 * {@code (invoiceId, finalizedAt)} cycle) and posts nothing, however many Kafka {@code eventId}s
 * carry it. Deposit-take invoices ({@code depositSourceType} non-null) fund a contract liability
 * rather than revenue (#1623) and are skipped, as are zero-total and never-finalized invoices.
 *
 * <p>The revenue entry's transaction date is the invoice's {@code finalizedAt} (business time),
 * never processing/clock time, so it lands in the invoice's month and redeliveries resolve the
 * same effective-dated GL mapping. The period gate applies inside
 * {@link JournalEntryService#postJournalEntry}; a CLOSED period, a missing mapping, or a
 * transient DB error propagates unwrapped for container retry / DLQ (ADR-0044 §4).
 *
 * <p>Each posting (and reversal) enqueues an {@link InvoiceGlPostedV1} fact on
 * {@code accounting.events.v1} through the transactional outbox, which pos-invoice consumes to
 * move the invoice {@code FINALIZED -> POSTED} with the real journal entry id. Publishing is a
 * no-op when the Kafka flag is off (the {@link OutboxEventWriter} bean is conditional).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvoiceRevenuePostingService {

    static final String POSTING_CATEGORY_NAME = "INVOICE_REVENUE";
    static final String ACCOUNTS_RECEIVABLE_KEY = "ACCOUNTS_RECEIVABLE";
    static final String SERVICE_REVENUE_KEY = "SERVICE_REVENUE";
    static final String SALES_TAX_PAYABLE_KEY = "SALES_TAX_PAYABLE";
    static final String SOURCE_EVENT_NAMESPACE = "INVOICE_REVENUE:";
    static final String REVERSAL_SOURCE_EVENT_NAMESPACE = "INVOICE_REVENUE_REVERSAL:";
    static final String SOURCE_SERVICE = "pos-accounting";
    static final String ACCOUNTING_EVENTS_TOPIC = DomainTopics.events("accounting");

    /** Invoice statuses whose fact recognizes revenue. */
    static final Set<String> POSTING_STATUSES = Set.of("FINALIZED", "POSTED");

    /** Invoice statuses whose fact reverses an open recognition. */
    static final Set<String> REVERSING_STATUSES = Set.of("DRAFT", "CANCELLED");

    private final Clock clock;
    private final GLMappingResolver glMappingResolver;
    private final GLPostingService glPostingService;
    private final InvoiceGlPostingRepository invoiceGlPostingRepository;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;

    /**
     * Post revenue recognition for a finalized invoice, exactly once per {@code (invoiceId,
     * finalizedAt)} cycle. Skips (with a log line) deposit-take invoices, zero/null totals,
     * facts without a {@code finalizedAt}, and cycles already posted or reversed.
     *
     * @param payload the consumed {@code invoice.invoice.updated} fact (status FINALIZED/POSTED)
     */
    @Transactional
    public void postRevenue(@NonNull InvoiceUpdatedV1 payload) {
        UUID invoiceId = payload.invoiceId();
        Instant finalizedAt = payload.finalizedAt();
        if (finalizedAt == null) {
            log.warn(
                    "Invoice fact status={} carries no finalizedAt, nothing to post | invoiceId={}",
                    payload.status(),
                    invoiceId);
            return;
        }
        if (payload.depositSourceType() != null) {
            log.info(
                    "Deposit-take invoice funds a contract liability, not revenue; skipping GL posting"
                            + " | invoiceId={} depositSourceType={}",
                    invoiceId,
                    payload.depositSourceType());
            return;
        }
        BigDecimal total = payload.total();
        if (total == null || total.signum() == 0) {
            log.info("Zero-total invoice, nothing to post | invoiceId={}", invoiceId);
            return;
        }
        if (invoiceGlPostingRepository
                .findByInvoiceIdAndReversalJournalEntryIdIsNull(invoiceId)
                .isPresent()) {
            log.info("Invoice revenue already posted (open posting), skipping | invoiceId={}", invoiceId);
            return;
        }
        if (invoiceGlPostingRepository.existsByInvoiceIdAndFinalizedAt(invoiceId, finalizedAt)) {
            log.info(
                    "Invoice revenue cycle already posted and reversed, skipping replay | invoiceId={} finalizedAt={}",
                    invoiceId,
                    finalizedAt);
            return;
        }

        BigDecimal tax = payload.tax() == null ? BigDecimal.ZERO : payload.tax();
        BigDecimal revenue = total.subtract(tax);

        // Business time, not processing time: the entry lands in the invoice's month and
        // redeliveries resolve the same effective-dated mapping.
        LocalDateTime transactionDate = LocalDateTime.ofInstant(finalizedAt, clock.getZone());
        Accounts accounts = resolveAccounts(transactionDate);

        UUID journalEntryId = glPostingService.postInvoiceRevenue(
                toSourceEventId(invoiceId, finalizedAt),
                invoiceId,
                accounts.accountsReceivable(),
                accounts.serviceRevenue(),
                accounts.salesTaxPayable(),
                revenue,
                tax,
                transactionDate,
                "Invoice revenue recognition - INV#" + displayNumber(payload));

        invoiceGlPostingRepository.save(InvoiceGlPosting.builder()
                .invoiceId(invoiceId)
                .finalizedAt(finalizedAt)
                .journalEntryId(journalEntryId)
                .postedAt(finalizedAt)
                .revenueAmount(revenue)
                .taxAmount(tax)
                .build());

        publishFact(new InvoiceGlPostedV1(
                invoiceId, journalEntryId, InvoiceGlPostedV1.PostingKind.POSTED, finalizedAt, finalizedAt, null));

        log.info(
                "Invoice revenue GL posting completed | invoiceId={} | invoiceNumber={} | revenue={} | tax={}"
                        + " | journalEntryId={}",
                invoiceId,
                payload.invoiceNumber(),
                revenue,
                tax,
                journalEntryId);
    }

    /**
     * Reverse an open revenue recognition when the invoice reverts to {@code DRAFT} or is
     * cancelled. No-op when the invoice has no open posting.
     *
     * @param payload the consumed {@code invoice.invoice.updated} fact (status DRAFT/CANCELLED)
     * @param occurredAt the fact's business time — the reversal entry's transaction date
     */
    @Transactional
    public void reverseRevenue(@NonNull InvoiceUpdatedV1 payload, @NonNull Instant occurredAt) {
        UUID invoiceId = payload.invoiceId();
        Optional<InvoiceGlPosting> open =
                invoiceGlPostingRepository.findByInvoiceIdAndReversalJournalEntryIdIsNull(invoiceId);
        if (open.isEmpty()) {
            log.debug(
                    "Invoice fact status={} has no open revenue posting, nothing to reverse | invoiceId={}",
                    payload.status(),
                    invoiceId);
            return;
        }
        InvoiceGlPosting posting = open.get();

        // Mirror what the ledger holds, not the revert fact's totals: a DRAFT fact may already
        // carry recomputed amounts, and the reversal must net the original entry to zero.
        BigDecimal revenue = posting.getRevenueAmount();
        BigDecimal tax = posting.getTaxAmount();

        // The revert's business time: the mirror lands in the current open period (period gate
        // applies), never a restatement of the original posting period.
        LocalDateTime transactionDate = LocalDateTime.ofInstant(occurredAt, clock.getZone());
        Accounts accounts = resolveAccounts(transactionDate);

        UUID reversalJournalEntryId = glPostingService.postInvoiceRevenueReversal(
                toReversalSourceEventId(invoiceId, posting.getFinalizedAt()),
                invoiceId,
                accounts.accountsReceivable(),
                accounts.serviceRevenue(),
                accounts.salesTaxPayable(),
                revenue,
                tax,
                transactionDate,
                "Invoice revenue reversal (" + payload.status() + ") - INV#" + displayNumber(payload));

        posting.setReversalJournalEntryId(reversalJournalEntryId);
        posting.setReversedAt(occurredAt);
        invoiceGlPostingRepository.save(posting);

        publishFact(new InvoiceGlPostedV1(
                invoiceId,
                reversalJournalEntryId,
                InvoiceGlPostedV1.PostingKind.REVERSED,
                posting.getFinalizedAt(),
                occurredAt,
                posting.getJournalEntryId()));

        log.info(
                "Invoice revenue GL reversal completed | invoiceId={} | status={} | revenue={} | tax={}"
                        + " | reversalJournalEntryId={} | reversedJournalEntryId={}",
                invoiceId,
                payload.status(),
                revenue,
                tax,
                reversalJournalEntryId,
                posting.getJournalEntryId());
    }

    private @NonNull Accounts resolveAccounts(@NonNull LocalDateTime transactionDate) {
        return new Accounts(
                glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, ACCOUNTS_RECEIVABLE_KEY, transactionDate),
                glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, SERVICE_REVENUE_KEY, transactionDate),
                glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, SALES_TAX_PAYABLE_KEY, transactionDate));
    }

    /**
     * Enqueue the {@code accounting.invoice.gl-posted} fact through the transactional outbox
     * (ADR-0044 §4). No-op when the Kafka flag is off — the writer bean is conditional.
     */
    private void publishFact(@NonNull InvoiceGlPostedV1 payload) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        DomainEventEnvelope<InvoiceGlPostedV1> envelope = DomainEventEnvelope.of(
                InvoiceGlPostedV1.EVENT_TYPE,
                InvoiceGlPostedV1.SCHEMA_VERSION,
                payload.invoiceId(),
                0L,
                SOURCE_SERVICE,
                null,
                null,
                payload,
                clock);
        writer.publish(ACCOUNTING_EVENTS_TOPIC, envelope);
        log.debug(
                "Queued {} invoiceId={} kind={} journalEntryId={}",
                InvoiceGlPostedV1.EVENT_TYPE,
                payload.invoiceId(),
                payload.postingKind(),
                payload.journalEntryId());
    }

    private static @NonNull String displayNumber(@NonNull InvoiceUpdatedV1 payload) {
        @Nullable String invoiceNumber = payload.invoiceNumber();
        return invoiceNumber == null || invoiceNumber.isBlank()
                ? payload.invoiceId().toString()
                : invoiceNumber;
    }

    /**
     * Derive the revenue entry's {@code sourceEventId} deterministically from the invoice id and
     * finalization instant, namespaced so it never collides with another entry deriving from the
     * same invoice.
     */
    static @NonNull UUID toSourceEventId(@NonNull UUID invoiceId, @NonNull Instant finalizedAt) {
        return UUID.nameUUIDFromBytes(
                (SOURCE_EVENT_NAMESPACE + invoiceId + ":" + finalizedAt).getBytes(StandardCharsets.UTF_8));
    }

    /** Reversal-entry counterpart of {@link #toSourceEventId} under its own namespace. */
    static @NonNull UUID toReversalSourceEventId(@NonNull UUID invoiceId, @NonNull Instant finalizedAt) {
        return UUID.nameUUIDFromBytes(
                (REVERSAL_SOURCE_EVENT_NAMESPACE + invoiceId + ":" + finalizedAt).getBytes(StandardCharsets.UTF_8));
    }

    /** The three resolved GL accounts of the {@code INVOICE_REVENUE} category. */
    private record Accounts(
            @NonNull UUID accountsReceivable,
            @NonNull UUID serviceRevenue,
            @NonNull UUID salesTaxPayable) {}
}
