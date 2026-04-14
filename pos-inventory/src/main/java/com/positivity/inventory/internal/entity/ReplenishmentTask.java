package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.ReplenishmentDecisionReason;
import com.positivity.inventory.internal.enums.ReplenishmentSourcingReason;
import com.positivity.inventory.internal.enums.ReplenishmentStatus;
import com.positivity.inventory.internal.enums.ReplenishmentTriggerType;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
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
@Table(name = "replenishment_task")
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReplenishmentTask {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID taskId;

    @Column(nullable = false)
    private String itemSKU;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false)
    private UUID sourceLocationId;

    @Column(nullable = false)
    private UUID destinationLocationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReplenishmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReplenishmentTriggerType triggerType;

    @Enumerated(EnumType.STRING)
    private ReplenishmentDecisionReason decisionReason;

    @Enumerated(EnumType.STRING)
    private ReplenishmentSourcingReason sourcingReason;

    private String assignedTo;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
