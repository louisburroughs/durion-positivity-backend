package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.PickListStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "inventory_pick_list", indexes = {
        @Index(name = "idx_inventory_pick_list_workorder_id", columnList = "workorder_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
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

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "pickList", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @Builder.Default
    private List<PickTaskEntity> pickTasks = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (status == null) {
            status = PickListStatus.DRAFT;
        }
    }
}
