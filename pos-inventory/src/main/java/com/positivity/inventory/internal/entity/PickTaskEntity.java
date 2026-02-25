package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.PickTaskStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "inventory_pick_task")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class PickTaskEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "pick_task_id", columnDefinition = "UUID")
    private UUID pickTaskId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pick_list_id", nullable = false)
    private PickListEntity pickList;

    @Column(name = "product_id", nullable = false, columnDefinition = "UUID")
    private UUID productId;

    @Column(nullable = false)
    private String sku;

    @Column(name = "quantity_required", nullable = false)
    private int quantityRequired;

    @Column(name = "quantity_picked", nullable = false)
    @Builder.Default
    private int quantityPicked = 0;

    @Column(name = "suggested_location_id", columnDefinition = "UUID")
    private UUID suggestedLocationId;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private int priority = 0;

    @Column(name = "due_at")
    private Instant dueAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PickTaskStatus status = PickTaskStatus.PENDING;

    @Column(name = "workorder_line_id", columnDefinition = "UUID")
    private UUID workorderLineId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (status == null) {
            status = PickTaskStatus.PENDING;
        }
    }
}
