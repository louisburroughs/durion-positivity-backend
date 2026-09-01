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
}
