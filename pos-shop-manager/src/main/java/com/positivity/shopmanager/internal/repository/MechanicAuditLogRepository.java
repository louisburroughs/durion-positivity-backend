package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.MechanicAuditLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MechanicAuditLogRepository extends JpaRepository<MechanicAuditLog, UUID> {}
