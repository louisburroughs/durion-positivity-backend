package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.PickListStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "inventory_pick_list", indexes = {
        @Index(name = "idx_inventory_pick_list_workorder_id", columnList = "workorder_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PickListEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "pick_list_id", columnDefinition = "UUID")
    private UUID pickListId;

    @Column(name = "workorder_id", nullable = false, columnDefinition = "UUID")
    private UUID workorderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PickListStatus status = PickListStatus.DRAFT;

    @Column(nullable = false)
    @Builder.Default
    private int priority = 0;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "pickList", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @Builder.Default
    private List<PickTaskEntity> pickTasks = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = PickListStatus.DRAFT;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}