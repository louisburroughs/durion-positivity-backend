package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.ReplenishmentSourceType;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "replenishment_policy")
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReplenishmentPolicy {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID policyId;

    @Column(nullable = false)
    private UUID locationId;

    @Column(nullable = false)
    private String itemSKU;

    @Column(nullable = false)
    private Integer minimumQuantity;

    @Column(nullable = false)
    private Integer maximumQuantity;

    /**
     * Round the computed replenishment quantity UP to the nearest multiple (odoo-parity F3,
     * issue #1041 — Odoo {@code qty_multiple}); {@code null} means no rounding. Seeded from
     * the vendor-feed pack size at creation when the caller supplies none.
     */
    private Integer orderMultiple;

    /**
     * Highest-precedence lead time in days (plan D-4: override → vendor feed → configured
     * default); {@code null} falls through to the feed lookup.
     */
    private Integer leadTimeDaysOverride;

    /** Sourcing preference consumed by parity-F5; stored only in F3. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ReplenishmentSourceType preferredSourceType = ReplenishmentSourceType.EITHER;

    /**
     * Policy suppressed from scan/event/needs evaluation while this instant is in the
     * future (Odoo orderpoint snooze); {@code null} or a past instant means active
     * evaluation.
     */
    private Instant snoozedUntil;

    /** Hard on/off switch; inactive policies are never evaluated. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @PrePersist
    void applyDefaults() {
        if (preferredSourceType == null) {
            preferredSourceType = ReplenishmentSourceType.EITHER;
        }
        if (active == null) {
            active = Boolean.TRUE;
        }
    }

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
