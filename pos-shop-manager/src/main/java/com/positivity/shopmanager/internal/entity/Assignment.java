package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shopmanager.internal.enums.AssignmentStatusEnum;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "assignment")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Assignment {

    @Id
    @Column(name = "assignment_id", columnDefinition = "UUID")
    private UUID assignmentId;

    @Column(name = "appointment_id", nullable = false, columnDefinition = "UUID")
    private UUID appointmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AssignmentStatusEnum status;

    @Column(name = "resource_id", columnDefinition = "UUID")
    private UUID resourceId;

    @Column(name = "resource_type", length = 50)
    private String resourceType;

    @Builder.Default
    @Column(name = "is_override", nullable = false)
    private boolean isOverride = false;

    @Column(name = "override_reason", length = 2000)
    private String overrideReason;

    @Builder.Default
    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void generateId() {
        if (assignmentId == null) {
            assignmentId = UUIDv7Generator.generate();
        }
    }
}
