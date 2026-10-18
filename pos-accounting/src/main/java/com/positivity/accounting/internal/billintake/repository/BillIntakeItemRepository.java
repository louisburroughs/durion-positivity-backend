package com.positivity.accounting.internal.billintake.repository;

import com.positivity.accounting.internal.billintake.entity.BillIntakeItem;
import com.positivity.accounting.internal.billintake.enums.BillIntakeChannel;
import com.positivity.accounting.internal.billintake.enums.BillIntakeStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Bill drafts (CAP:550 S25, #2518). Read and written only by the bill-intake services. */
public interface BillIntakeItemRepository extends JpaRepository<BillIntakeItem, UUID> {

    /** Drafts in a status. */
    long countByStatus(@NonNull BillIntakeStatus status);

    /** Drafts in a status on a channel: the {@code accounting.bill_intake.items} gauge. */
    long countByStatusAndChannel(@NonNull BillIntakeStatus status, @NonNull BillIntakeChannel channel);

    /** The draft a replayed upload request already made (the request id of that file of the request). */
    @NonNull
    Optional<BillIntakeItem> findByChannelAndRequestId(@NonNull BillIntakeChannel channel, @NonNull UUID requestId);

    /** The oldest draft in a status: the age of the quarantine backlog. */
    @NonNull
    Optional<BillIntakeItem> findFirstByStatusOrderByUploadedAtAscIntakeIdAsc(@NonNull BillIntakeStatus status);

    /** Every draft in a status: the drafts a new draft is checked against for a duplicate. */
    @NonNull
    List<BillIntakeItem> findByStatus(@NonNull BillIntakeStatus status);

    /** Whether any live (not rejected, not discarded) draft still reads this source file. */
    boolean existsBySourceFileIdAndStatusIn(@NonNull UUID sourceFileId, @NonNull Collection<BillIntakeStatus> statuses);

    /**
     * Claims the oldest draft of a status and channel that is due, with a row lock taken with SKIP LOCKED (lock timeout
     * -2): a concurrent processor on another instance skips the row instead of waiting. Pass a page of one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
        SELECT i
        FROM BillIntakeItem i
        WHERE i.status = :status
          AND i.channel = :channel
          AND (i.nextAttemptAt IS NULL OR i.nextAttemptAt <= :now)
        ORDER BY i.uploadedAt ASC, i.intakeId ASC
        """)
    @NonNull
    List<BillIntakeItem> claimDue(
            @Param("status") @NonNull BillIntakeStatus status,
            @Param("channel") @NonNull BillIntakeChannel channel,
            @Param("now") @NonNull Instant now,
            @NonNull Pageable page);
}
