package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.HrIntegrationLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HrIntegrationLogRepository extends JpaRepository<HrIntegrationLog, UUID> {
    boolean existsByEventId(UUID eventId);
}
