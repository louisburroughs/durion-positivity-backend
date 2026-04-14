package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.CycleCountPlanStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
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
@Table(name = "cycle_count_plan")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class CycleCountPlan {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "plan_id", updatable = false, nullable = false)
    private UUID planId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "cycle_count_plan_zone", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "zone_id")
    private List<UUID> zoneIds;

    @Column(name = "plan_name")
    private String planName;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private CycleCountPlanStatus status;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
