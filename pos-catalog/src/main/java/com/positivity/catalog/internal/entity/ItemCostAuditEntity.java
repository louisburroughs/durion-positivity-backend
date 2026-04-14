package com.positivity.catalog.internal.entity;

import com.positivity.catalog.internal.enums.ChangeSourceType;
import com.positivity.catalog.internal.enums.CostType;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@Setter
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "item_cost_audit",
        indexes = {
            @Index(name = "idx_item_cost_audit_item_id", columnList = "item_id"),
            @Index(name = "idx_item_cost_audit_timestamp", columnList = "audit_timestamp")
        })
public class ItemCostAuditEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "audit_id", nullable = false, columnDefinition = "UUID")
    private UUID auditId;

    @Column(name = "item_id", nullable = false, columnDefinition = "UUID")
    private UUID itemId;

    @Column(name = "audit_timestamp", nullable = false)
    private Instant timestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_type_changed", nullable = false)
    private CostType costTypeChanged;

    @Column(name = "old_value", precision = 19, scale = 4)
    private BigDecimal oldValue;

    @Column(name = "new_value", precision = 19, scale = 4)
    private BigDecimal newValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_source_type", nullable = false)
    private ChangeSourceType changeSourceType;

    @Column(name = "change_source_id", nullable = false)
    private String changeSourceId;

    @Column(name = "actor", nullable = false)
    private String actor;

    @Column(name = "reason_code")
    private String reasonCode;

    @PrePersist
    void onCreate() {
        if (timestamp == null) {
            timestamp = Instant.now(Clock.systemUTC());
        }
    }
}
