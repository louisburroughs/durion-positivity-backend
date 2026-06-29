package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ReconciliationRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for reconciliation records created when posting retries are
 * exhausted.
 */
public interface ReconciliationRecordRepository extends JpaRepository<ReconciliationRecord, UUID> {

    List<ReconciliationRecord> findByInvoiceId(UUID invoiceId);
}
