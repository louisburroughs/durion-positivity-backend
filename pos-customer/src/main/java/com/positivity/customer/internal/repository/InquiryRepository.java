package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.Inquiry;
import com.positivity.customer.internal.enums.InquiryStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InquiryRepository extends JpaRepository<Inquiry, UUID> {

    Page<Inquiry> findByStatusOrderByCreatedAtDesc(InquiryStatus status, Pageable pageable);

    Page<Inquiry> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Submissions from one source since a cut-off. Backs the public form's abuse
     * check without
     * keeping per-request state in memory, so the limit survives a restart and is
     * shared across
     * instances.
     */
    @Query("SELECT COUNT(i) FROM Inquiry i WHERE i.submittedFrom = :source AND i.createdAt >= :since")
    long countRecentFromSource(@Param("source") String source, @Param("since") Instant since);
}
