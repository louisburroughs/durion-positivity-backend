package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.InvoiceRegenerationRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRegenerationRequestRepository extends JpaRepository<InvoiceRegenerationRequest, UUID> {

    @NonNull
    Optional<InvoiceRegenerationRequest> findByIdempotencyKey(@NonNull String idempotencyKey);

    @NonNull
    List<InvoiceRegenerationRequest> findByWorkorderIdAndStatus(@NonNull UUID workorderId, @NonNull String status);

    /**
     * Requests still in {@code status} whose {@code requestedAt} is at or before {@code cutoff}
     * (issue #1537 F4) — the reap candidates for {@code WorkorderEventsListener#reapExpiredRequests}.
     */
    @NonNull
    List<InvoiceRegenerationRequest> findByStatusAndRequestedAtBefore(@NonNull String status, @NonNull Instant cutoff);
}
