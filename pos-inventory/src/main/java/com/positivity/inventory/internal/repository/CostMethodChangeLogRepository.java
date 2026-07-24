package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.CostMethodChangeLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for the append-only {@link CostMethodChangeLog} (odoo-parity J1, issue #1048). */
public interface CostMethodChangeLogRepository extends JpaRepository<CostMethodChangeLog, UUID> {}
