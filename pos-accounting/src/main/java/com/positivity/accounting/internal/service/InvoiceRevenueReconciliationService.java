package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.InvoiceRevenueReconcileRequest;
import com.positivity.accounting.internal.dto.InvoiceRevenueReconcileResponse;
import com.positivity.accounting.internal.dto.InvoiceRevenueReconcileResponse.Kind;
import com.positivity.accounting.internal.dto.InvoiceRevenueReconcileResponse.Outcome;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.InvoiceGlPosting;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.InvoiceGlPostingRepository;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Reconciles the ledger with the invoice replica (#1851): every {@code FINALIZED}/{@code POSTED}
 * invoice in {@code ext_invoice} without an open revenue posting gets one, through the same
 * {@link InvoiceRevenuePostingService} the live event path uses.
 *
 * <p>Why the replica and not a replay: invoices finalized before revenue posting existed (#1843)
 * mostly have no outbox event to replay, a replay re-sends the eventIds accounting already
 * deduplicates, and the replay command's lookback is bounded. The replica is kept current by the
 * event flow and carries every field {@code postRevenue} reads, so the question "which finalized
 * invoices are not on the ledger?" can be asked on any day, in any environment, and answered
 * idempotently: the {@code (invoice_id, finalized_at)} key in {@code invoice_gl_posting} makes a
 * second run — or a run racing the live listener — a no-op.
 *
 * <p>Each invoice posts in its own transaction (inside {@code postRevenue}); one failure is
 * reported and does not stop the run. Skips mirror the live path's rules so the outcome list says
 * why an invoice carries no revenue rather than silently omitting it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceRevenueReconciliationService {

    /** Far enough back to cover any seeded history; the window is half-open at {@code finalizedTo}. */
    static final Instant EARLIEST = Instant.parse("2000-01-01T00:00:00Z");

    private final ExtInvoiceRepository extInvoiceRepository;
    private final InvoiceGlPostingRepository invoiceGlPostingRepository;
    private final InvoiceRevenuePostingService invoiceRevenuePostingService;
    private final Clock clock;

    public @NonNull InvoiceRevenueReconcileResponse reconcile(@NonNull InvoiceRevenueReconcileRequest request) {
        List<ExtInvoice> candidates = candidates(request);
        List<Outcome> outcomes = new ArrayList<>(candidates.size());
        int posted = 0;
        int alreadyPosted = 0;
        int skipped = 0;
        int failed = 0;
        for (ExtInvoice invoice : candidates) {
            Outcome outcome = reconcileOne(invoice, request.dryRunEnabled());
            outcomes.add(outcome);
            switch (outcome.kind()) {
                case POSTED, WOULD_POST -> posted++;
                case ALREADY_POSTED -> alreadyPosted++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
        }
        log.info(
                "Invoice revenue reconciliation {} | scanned={} posted={} alreadyPosted={} skipped={} failed={}"
                        + " window=[{}, {}) invoiceIds={}",
                request.dryRunEnabled() ? "dry run" : "completed",
                candidates.size(),
                posted,
                alreadyPosted,
                skipped,
                failed,
                request.finalizedFrom(),
                request.finalizedTo(),
                request.invoiceIds() == null ? 0 : request.invoiceIds().size());
        return new InvoiceRevenueReconcileResponse(
                request.dryRunEnabled(),
                candidates.size(),
                posted,
                alreadyPosted,
                skipped,
                failed,
                List.copyOf(outcomes));
    }

    private @NonNull List<ExtInvoice> candidates(@NonNull InvoiceRevenueReconcileRequest request) {
        List<ExtInvoice> rows;
        if (request.invoiceIds() != null && !request.invoiceIds().isEmpty()) {
            rows = extInvoiceRepository.findAllById(request.invoiceIds()).stream()
                    .filter(invoice -> InvoiceRevenuePostingService.POSTING_STATUSES.contains(invoice.getStatus()))
                    .sorted((a, b) -> compareFinalizedAt(a.getFinalizedAt(), b.getFinalizedAt()))
                    .toList();
        } else {
            Instant from = request.finalizedFrom() == null ? EARLIEST : request.finalizedFrom();
            // Half-open upper bound; "now plus a day" keeps clock skew between services out of it.
            Instant to =
                    request.finalizedTo() == null ? Instant.now(clock).plus(Duration.ofDays(1)) : request.finalizedTo();
            rows =
                    extInvoiceRepository
                            .findByStatusInAndFinalizedAtGreaterThanEqualAndFinalizedAtLessThanOrderByFinalizedAtAsc(
                                    InvoiceRevenuePostingService.POSTING_STATUSES, from, to);
        }
        if (request.limit() != null && rows.size() > request.limit()) {
            return rows.subList(0, request.limit());
        }
        return rows;
    }

    private static int compareFinalizedAt(Instant a, Instant b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : 1) : -1;
        }
        return a.compareTo(b);
    }

    private @NonNull Outcome reconcileOne(@NonNull ExtInvoice invoice, boolean dryRun) {
        Instant finalizedAt = invoice.getFinalizedAt();
        BigDecimal total = invoice.getTotal();
        if (finalizedAt == null) {
            return outcome(invoice, Kind.SKIPPED, "no finalizedAt on the replica row");
        }
        if (invoice.getDepositSourceType() != null) {
            return outcome(invoice, Kind.SKIPPED, "deposit-take invoice funds a contract liability, not revenue");
        }
        if (total == null || total.signum() == 0) {
            return outcome(invoice, Kind.SKIPPED, "zero or missing total");
        }
        Optional<InvoiceGlPosting> open =
                invoiceGlPostingRepository.findByInvoiceIdAndReversalJournalEntryIdIsNull(invoice.getInvoiceId());
        if (open.isPresent()) {
            return outcome(
                    invoice, Kind.ALREADY_POSTED, "journalEntryId=" + open.get().getJournalEntryId());
        }
        if (invoiceGlPostingRepository.existsByInvoiceIdAndFinalizedAt(invoice.getInvoiceId(), finalizedAt)) {
            return outcome(invoice, Kind.SKIPPED, "this finalization was posted and reversed already");
        }
        if (dryRun) {
            return outcome(invoice, Kind.WOULD_POST, null);
        }
        try {
            invoiceRevenuePostingService.postRevenue(toPayload(invoice));
        } catch (RuntimeException failure) {
            log.warn(
                    "Invoice revenue reconciliation failed for invoiceId={} finalizedAt={}: {}",
                    invoice.getInvoiceId(),
                    finalizedAt,
                    failure.toString());
            return outcome(invoice, Kind.FAILED, failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        return invoiceGlPostingRepository
                .findByInvoiceIdAndReversalJournalEntryIdIsNull(invoice.getInvoiceId())
                .map(posting -> outcome(invoice, Kind.POSTED, "journalEntryId=" + posting.getJournalEntryId()))
                .orElseGet(() -> outcome(invoice, Kind.FAILED, "posting service returned without creating an entry"));
    }

    /** The replica row in the shape the live listener hands to {@code postRevenue}. */
    static @NonNull InvoiceUpdatedV1 toPayload(@NonNull ExtInvoice invoice) {
        return new InvoiceUpdatedV1(
                invoice.getInvoiceId(),
                invoice.getInvoiceNumber(),
                invoice.getWorkorderId(),
                invoice.getEstimateId(),
                invoice.getLocationId(),
                invoice.getPartyId(),
                invoice.getStatus(),
                invoice.getSubtotal(),
                invoice.getTax(),
                invoice.getTotal(),
                invoice.getAdjustmentsAmount(),
                invoice.getInvoiceCreatedAt(),
                invoice.getFinalizedAt(),
                null,
                null,
                invoice.getDueDate(),
                null,
                invoice.getDepositSourceType(),
                null);
    }

    private static @NonNull Outcome outcome(@NonNull ExtInvoice invoice, @NonNull Kind kind, String detail) {
        return new Outcome(
                invoice.getInvoiceId(),
                invoice.getInvoiceNumber(),
                invoice.getFinalizedAt(),
                invoice.getTotal(),
                kind,
                detail);
    }
}
