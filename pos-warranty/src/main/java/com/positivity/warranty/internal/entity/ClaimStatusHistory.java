package com.positivity.warranty.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.warranty.internal.enums.ClaimStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Append-only claim status transition (PRD §3.9) — every status change, decision, and override
 * is recorded because vendor charge-backs and audits are a fact of life in warranty programs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "claim_status_history", indexes = @Index(name = "idx_chistory_claim", columnList = "claim_id"))
@EntityListeners(AuditingEntityListener.class)
public class ClaimStatusHistory {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    /** Null for the initial DRAFT transition. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private ClaimStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32)
    private ClaimStatus toStatus;

    @Column(name = "actor")
    private String actor;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
