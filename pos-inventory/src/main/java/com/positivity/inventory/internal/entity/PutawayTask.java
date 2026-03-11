package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.PutawayFallbackReason;
import com.positivity.inventory.internal.enums.PutawayTaskStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "putaway_task")
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PutawayTask {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID taskId;

    @ManyToOne(optional = false)
    @JoinColumn(name = "source_receipt_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private GoodsReceiptEntity sourceReceipt;

    @Column(nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false)
    private UUID sourceLocationId;

    private UUID suggestedDestinationLocationId;

    private UUID originalSuggestedLocationId;

    private UUID finalSuggestedLocationId;

    private UUID actualDestinationLocationId;

    @Enumerated(EnumType.STRING)
    private PutawayFallbackReason fallbackReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PutawayTaskStatus status;

    private String assigneeId;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
