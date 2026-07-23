package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ScrapRecord;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Repository for {@link ScrapRecord} documents (odoo-parity D1, issue #1030).
 *
 * <p>List filtering (reason/status/location/date) goes through the
 * {@link JpaSpecificationExecutor} so optional filter combinations stay in one query.
 */
public interface ScrapRecordRepository
        extends JpaRepository<ScrapRecord, UUID>, JpaSpecificationExecutor<ScrapRecord> {}
