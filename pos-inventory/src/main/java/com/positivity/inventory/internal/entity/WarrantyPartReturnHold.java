package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Quarantine/hold tracking record for a warranty defective-part return (issue #927), materialized
 * from {@code warranty.events.v1}. pos-warranty owns the part-return lifecycle; nothing in this
 * module may write the table except the event consumer. The physical hold shelf stays a manual
 * process in v1 — this row only tracks what should be on it ({@code REQUESTED}) and what has left
 * it ({@code SHIPPED}).
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "warranty_part_return_hold")
public class WarrantyPartReturnHold {

    /** Part is expected on the quarantine shelf (set on {@code warranty.part-return.requested}). */
    public static final String STATUS_REQUESTED = "REQUESTED";

    /** Part has been shipped back to the provider (set on {@code warranty.part-return.shipped}). */
    public static final String STATUS_SHIPPED = "SHIPPED";

    @Id
    @Column(name = "part_return_id", nullable = false, updatable = false)
    private UUID partReturnId;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Column(name = "claim_line_id")
    private UUID claimLineId;

    @Column(name = "product_entity_id")
    private UUID productEntityId;

    @Column(name = "serial_number", length = 128)
    private String serialNumber;

    @Column(name = "disposition", length = 64)
    private String disposition;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "carrier", length = 64)
    private String carrier;

    @Column(name = "tracking_number", length = 128)
    private String trackingNumber;

    @Column(name = "requested_at")
    private Instant requestedAt;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the primary key IS a
     * UUIDv7 minted by pos-warranty; this tracking record stores it verbatim.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
