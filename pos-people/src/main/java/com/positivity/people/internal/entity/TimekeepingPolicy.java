package com.positivity.people.internal.entity;

import com.positivity.people.internal.enums.TimekeepingPolicyScopeType;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "timekeeping_policy", indexes = {
        @Index(name = "idx_timekeeping_policy_scope", columnList = "scope_type, scope_id"),
        @Index(name = "idx_timekeeping_policy_scope_effective_updated",
                columnList = "scope_type, scope_id, effective_start_at, effective_end_at, updated_at")
})
public class TimekeepingPolicy {

    @Id
    @Column(name = "timekeeping_policy_id", columnDefinition = "UUID", nullable = false)
    private UUID timekeepingPolicyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 16)
    private TimekeepingPolicyScopeType scopeType;

    @Column(name = "scope_id")
    private UUID scopeId;

    @Column(name = "job_time_discrepancy_threshold_minutes", nullable = false)
    private Integer jobTimeDiscrepancyThresholdMinutes;

    @Column(name = "effective_start_at")
    private Instant effectiveStartAt;

    @Column(name = "effective_end_at")
    private Instant effectiveEndAt;

    @Column(name = "updated_by")
    private String updatedBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onPrePersist() {
        if (timekeepingPolicyId == null) {
            timekeepingPolicyId = UUIDv7Generator.generate();
        }
    }

    public UUID getTimekeepingPolicyId() {
        return timekeepingPolicyId;
    }

    public void setTimekeepingPolicyId(UUID timekeepingPolicyId) {
        this.timekeepingPolicyId = timekeepingPolicyId;
    }

    public TimekeepingPolicyScopeType getScopeType() {
        return scopeType;
    }

    public void setScopeType(TimekeepingPolicyScopeType scopeType) {
        this.scopeType = scopeType;
    }

    public UUID getScopeId() {
        return scopeId;
    }

    public void setScopeId(UUID scopeId) {
        this.scopeId = scopeId;
    }

    public Integer getJobTimeDiscrepancyThresholdMinutes() {
        return jobTimeDiscrepancyThresholdMinutes;
    }

    public void setJobTimeDiscrepancyThresholdMinutes(Integer jobTimeDiscrepancyThresholdMinutes) {
        this.jobTimeDiscrepancyThresholdMinutes = jobTimeDiscrepancyThresholdMinutes;
    }

    public Instant getEffectiveStartAt() {
        return effectiveStartAt;
    }

    public void setEffectiveStartAt(Instant effectiveStartAt) {
        this.effectiveStartAt = effectiveStartAt;
    }

    public Instant getEffectiveEndAt() {
        return effectiveEndAt;
    }

    public void setEffectiveEndAt(Instant effectiveEndAt) {
        this.effectiveEndAt = effectiveEndAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
