package com.positivity.workorder.internal.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "work_order_state_transitions")
public class WorkorderStateTransition {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID workorderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkorderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkorderStatus toStatus;

    @Column(nullable = false)
    private Instant transitionedAt;

    @Column(nullable = false)
    private UUID transitionedBy;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @PrePersist
    protected void onCreate() {
        if (transitionedAt == null) {
            transitionedAt = Instant.now();
        }
    }
}
