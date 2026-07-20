package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.FeeRepresentation;
import com.positivity.accounting.internal.enums.MatchReferenceField;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Read-only replica of the payment service's per-provider settlement matching configuration
 * (ADR-0044 §3, story F1c, decision D-9), materialized from the compacted {@code
 * payment.settlement-config.v1} topic keyed by {@code providerCode} (last-writer-wins). The payment
 * service is the sole owner; accounting only reads it during settlement matching/posting.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "ext_payment_settlement_config")
public class ExtPaymentSettlementConfig {

    @Id
    @Column(name = "provider_code", length = 64, nullable = false, updatable = false)
    private String providerCode;

    /** {@code SettlementProviderConfigV1.eventId} of the last applied revision. */
    @Column(name = "event_id", columnDefinition = "UUID", nullable = false)
    private UUID eventId;

    @Column(name = "provider_name", length = 128)
    private String providerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_reference_field", length = 32, nullable = false)
    private MatchReferenceField matchReferenceField;

    @Column(name = "amount_tolerance", precision = 19, scale = 4, nullable = false)
    private BigDecimal amountTolerance;

    @Enumerated(EnumType.STRING)
    @Column(name = "fee_representation", length = 32, nullable = false)
    private FeeRepresentation feeRepresentation;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** ArchUnit UUIDv7 rule hook (ADR-0013, generator-owned upstream). */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
