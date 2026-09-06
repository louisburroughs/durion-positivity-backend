package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.InvoiceGlPosting;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for invoice revenue-recognition posting cycles (issue #1843). */
public interface InvoiceGlPostingRepository extends JpaRepository<InvoiceGlPosting, UUID> {

    /** The invoice's open (un-reversed) posting, if any — at most one exists (V36 partial unique index). */
    Optional<InvoiceGlPosting> findByInvoiceIdAndReversalJournalEntryIdIsNull(@NonNull UUID invoiceId);

    /** Whether the {@code (invoice, finalizedAt)} cycle was ever posted, open or reversed. */
    boolean existsByInvoiceIdAndFinalizedAt(@NonNull UUID invoiceId, @NonNull Instant finalizedAt);
}
