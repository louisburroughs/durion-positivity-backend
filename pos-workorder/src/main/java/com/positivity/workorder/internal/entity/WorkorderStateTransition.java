package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.time.TimeSource;
import com.positivity.workorder.internal.enums.WorkorderStatus;
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
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"workorder"})
@Table(name = "work_order_state_transitions")
@EntityListeners(AuditingEntityListener.class)
public class WorkorderStateTransition {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workorder_id", nullable = false)
    private Workorder workorder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkorderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkorderStatus toStatus;

    @Column(nullable = false)
    private Instant transitionedAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private String transitionedBy;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Transient
    public UUID getWorkorderId() {
        return workorder != null ? workorder.getId() : null;
    }

    public void setWorkorderId(UUID workorderId) {
        this.workorder = workorderId != null ? new Workorder(workorderId) : null;
    }

    @PrePersist
    protected void prePersist() {
        if (transitionedAt == null) {
            transitionedAt = TimeSource.instant();
        }
    }
}
