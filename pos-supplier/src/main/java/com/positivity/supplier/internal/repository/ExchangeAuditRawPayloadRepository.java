package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.ExchangeAuditRawPayloadEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Raw, still-encrypted payload bytes of one exchange-audit row.
 *
 * <p>The only read path that touches payload content, and deliberately the only one that returns
 * ciphertext to application code: decryption is performed explicitly by the audit read service so a
 * failure lands in ordinary code rather than inside Hibernate's hydration, keeping the ADR-0050 §7
 * access record writable when the payload is unreadable. See
 * {@link ExchangeAuditRawPayloadEntity} for why that matters.
 */
public interface ExchangeAuditRawPayloadRepository extends JpaRepository<ExchangeAuditRawPayloadEntity, UUID> {}
