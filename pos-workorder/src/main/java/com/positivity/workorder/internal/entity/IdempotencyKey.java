package com.positivity.workorder.internal.entity;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Id;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Entity for tracking idempotency keys to prevent duplicate workorder creation.
 * 
 * <p>
 * This entity stores idempotency keys submitted with workorder creation
 * requests
 * to ensure that retrying a failed request with the same key does not create
 * duplicate workorders. Keys expire after 24 hours.
 * </p>
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "idempotency_keys", indexes = {
        @Index(name = "idx_key_value", columnList = "keyValue", unique = true)
})
public class IdempotencyKey {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String keyValue;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workorder_id")
    @ToString.Exclude
    private Workorder workorder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "change_request_id")
    @ToString.Exclude
    private ChangeRequest changeRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "labor_entry_id")
    @ToString.Exclude
    private WorkorderLaborEntry laborEntry;

    @Transient
    public UUID getWorkorderId() {
        return workorder != null ? workorder.getId() : null;
    }

    @Transient
    public void setWorkorderId(UUID workorderId) {
        this.workorder = new Workorder(workorderId);
    }

    @Transient
    public UUID getChangeRequestId() {
        return changeRequest != null ? changeRequest.getId() : null;
    }

    @Transient
    public void setChangeRequestId(UUID changeRequestId) {
        this.changeRequest = new ChangeRequest(changeRequestId);
    }

    @Transient
    public UUID getLaborEntryId() {
        return laborEntry != null ? laborEntry.getId() : null;
    }

    @Transient
    public void setLaborEntryId(UUID laborEntryId) {
        this.laborEntry = new WorkorderLaborEntry(laborEntryId);
    }

    @Column(columnDefinition = "UUID")
    private UUID partUsageEventId;

    @Column(columnDefinition = "UUID")
    private UUID partAdjustmentEventId;

    @Column(columnDefinition = "UUID")
    private UUID invoiceId;

    @CreatedDate
    @Column(nullable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    public IdempotencyKey(String keyValue, Workorder workorder, Instant expiresAt) {
        this.keyValue = keyValue;
        this.workorder = workorder;
        this.expiresAt = expiresAt;
    }

    public IdempotencyKey(String keyValue, Workorder workorder, ChangeRequest changeRequest, Instant expiresAt) {
        this.keyValue = keyValue;
        this.workorder = workorder;
        this.changeRequest = changeRequest;
        this.expiresAt = expiresAt;
    }
}
