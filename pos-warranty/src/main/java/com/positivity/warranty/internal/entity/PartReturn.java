package com.positivity.warranty.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.warranty.internal.enums.PartReturnDisposition;
import com.positivity.warranty.internal.enums.PartReturnStatus;
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
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Defective-part RMA (PRD §3.8), at most one per claim line. Created when the governing policy
 * has {@code requiresPartReturn} or staff choose to return the part; the physical hold shelf is
 * manual in v1 with this record as the system of record. {@code claimId} is denormalized for
 * worklist queries (the line already belongs to exactly one claim).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "part_return",
        indexes = {
            @Index(name = "idx_preturn_claim", columnList = "claim_id"),
            @Index(name = "idx_preturn_status", columnList = "status")
        })
@EntityListeners(AuditingEntityListener.class)
public class PartReturn {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Column(name = "claim_line_id", nullable = false, unique = true)
    private UUID claimLineId;

    /** Vendor-issued RMA number. */
    @Column(name = "rma_number", length = 100)
    private String rmaNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "disposition", nullable = false, length = 32)
    private PartReturnDisposition disposition;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PartReturnStatus status = PartReturnStatus.AWAITING_PART;

    @Column(name = "carrier", length = 100)
    private String carrier;

    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "hold_location_note", columnDefinition = "text")
    private String holdLocationNote;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;
}
