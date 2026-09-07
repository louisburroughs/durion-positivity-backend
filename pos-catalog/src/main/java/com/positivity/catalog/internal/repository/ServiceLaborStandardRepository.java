package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ServiceLaborStandardEntity;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceLaborStandardRepository extends JpaRepository<ServiceLaborStandardEntity, UUID> {

    /** Active (non-superseded) standards for a service — the everyday read. */
    @NonNull
    List<ServiceLaborStandardEntity> findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(@NonNull UUID serviceId);

    /** Full history including superseded rows, for audit views. */
    @NonNull
    List<ServiceLaborStandardEntity> findByServiceIdOrderByCreatedAtAsc(@NonNull UUID serviceId);

    /** Active rows one source holds for one service — the import's upsert-by-natural-key read. */
    @NonNull
    List<ServiceLaborStandardEntity> findByServiceIdAndSourceCodeAndSupersededAtIsNull(
            @NonNull UUID serviceId, @NonNull String sourceCode);

    /**
     * Every active row across every service, for the cross-source conflict sweep (#1569 R2).
     *
     * <p>A full scan is honest at reference-catalog volume and is what the curation report needs
     * — a conflict is by definition a comparison between rows the everyday per-service reads
     * never fetch together. The Phase 2 scale pass moves this behind a windowed query when a
     * licensed feed makes the table large; it is called by an admin report, not the quote path.
     */
    @NonNull
    List<ServiceLaborStandardEntity> findBySupersededAtIsNullOrderByServiceIdAsc();
}
