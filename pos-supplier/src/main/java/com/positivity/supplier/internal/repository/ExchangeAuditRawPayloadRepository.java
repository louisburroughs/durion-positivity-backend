package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.ExchangeAuditRawPayloadEntity;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.repository.Repository;

/**
 * Raw, still-encrypted payload bytes of <em>one</em> exchange-audit row.
 *
 * <p>The only read path that touches payload content, and deliberately the only one that returns ciphertext
 * to application code: decryption is performed explicitly by the audit read service so a failure lands in
 * ordinary code rather than inside Hibernate's hydration, keeping the ADR-0050 §7 access record writable when
 * the payload is unreadable. See {@link ExchangeAuditRawPayloadEntity} for why that matters.
 *
 * <p>Extends {@link Repository}, not {@code JpaRepository}, and declares exactly one method. That is a
 * containment decision. {@code JpaRepository} would have handed this type {@code findAll()},
 * {@code findAllById(...)} and a {@code Pageable} overload — a supported, discoverable way to pull every
 * stored ciphertext in the table out in one call, on the one type in the module that bypasses the encryption
 * converter. Nothing needs it, ADR-0050 §7 grants payload access one row at a time behind a recorded read,
 * and an inherited bulk accessor is exactly the affordance a later change reaches for without noticing what
 * it defeats. The surface is therefore the single lookup the read path uses.
 *
 * <p>The mutating half of {@code CrudRepository} is absent for the same reason it should be: this mapping is
 * {@code @Immutable} and writes belong to {@code ExchangeAuditEntity}, which encrypts.
 */
public interface ExchangeAuditRawPayloadRepository extends Repository<ExchangeAuditRawPayloadEntity, UUID> {

    /**
     * One row's stored payload bytes.
     *
     * @param exchangeAuditId identity shared with the exchange-audit row
     * @return the raw envelopes, or empty when no such row exists
     */
    @NonNull
    Optional<ExchangeAuditRawPayloadEntity> findById(@NonNull UUID exchangeAuditId);
}
