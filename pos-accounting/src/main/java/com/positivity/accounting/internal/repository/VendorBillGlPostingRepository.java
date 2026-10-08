package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.VendorBillGlPosting;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The postings of approved vendor bills, one per bill (#2509, AW37). */
public interface VendorBillGlPostingRepository extends JpaRepository<VendorBillGlPosting, UUID> {

    Optional<VendorBillGlPosting> findByVendorBillId(UUID vendorBillId);

    /** The posting whose entry is {@code journalEntryId}: the entry a bill's approval posted. */
    Optional<VendorBillGlPosting> findByJournalEntryId(UUID journalEntryId);

    /** The posting whose void reversed it with {@code reversalJournalEntryId}. */
    Optional<VendorBillGlPosting> findByReversalJournalEntryId(UUID reversalJournalEntryId);
}
