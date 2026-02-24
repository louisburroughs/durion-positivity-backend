package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.PutawayFallbackReason;
import com.positivity.inventory.internal.enums.PutawayTaskStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "putaway_task")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PutawayTask {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID taskId;

    @Column(nullable = false)
    private UUID sourceReceiptId;

    @Column(nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false)
    private String sourceLocationId;

    private String suggestedDestinationLocationId;

    private String originalSuggestedLocationId;

    private String finalSuggestedLocationId;

    private String actualDestinationLocationId;

    @Enumerated(EnumType.STRING)
    private PutawayFallbackReason fallbackReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PutawayTaskStatus status;

    private String assigneeId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
