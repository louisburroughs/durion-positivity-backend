package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.RevaluationRecord;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Repository for manual cost-revaluation documents (odoo-parity J4, issue #1054). */
public interface RevaluationRecordRepository
        extends JpaRepository<RevaluationRecord, UUID>, JpaSpecificationExecutor<RevaluationRecord> {}
