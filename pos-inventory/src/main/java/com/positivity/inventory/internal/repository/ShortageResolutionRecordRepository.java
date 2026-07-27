package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ShortageResolutionRecord;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link ShortageResolutionRecord} idempotency rows (odoo-parity G2, issue #1049). */
public interface ShortageResolutionRecordRepository extends JpaRepository<ShortageResolutionRecord, UUID> {

    @NonNull
    Optional<ShortageResolutionRecord> findByIdempotencyKey(@NonNull String idempotencyKey);
}
