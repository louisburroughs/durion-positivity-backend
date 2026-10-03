package com.positivity.vehiclefitment.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantGlobal;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@Entity
@TenantGlobal(
        reason = "fitment reference data shared by every tenant (ADR-0062 section 5, db/tenancy-global-tables.txt)")
@EntityListeners(AuditingEntityListener.class)
@Table(name = "model")
public class Model {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    /**
     * vPIC's own numeric id for this row (#2416) — the id vPIC's dependent lookups take in their path.
     * {@code null} when the row did not come from vPIC (seeded, bulk-loaded, or created by a fitment
     * request); such a row cannot be refreshed from vPIC.
     */
    @Column(name = "nhtsa_id")
    private Long nhtsaId;

    private String name;

    @ManyToOne
    private Make make; // Reference to the Make entity

    private LocalDateTime cacheTimestamp;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
