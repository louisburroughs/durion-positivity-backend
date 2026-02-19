package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ApprovalRequestEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequestEntity, UUID> {

    Optional<ApprovalRequestEntity> findByOverrideId(UUID overrideId);
}
