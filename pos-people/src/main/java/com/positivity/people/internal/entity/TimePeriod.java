package com.positivity.people.internal.entity;

import com.positivity.people.internal.enums.TimePeriodStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "time_period",
        indexes = {
            @Index(name = "idx_time_period_tenant_status", columnList = "tenant_id, status"),
            @Index(name = "idx_time_period_dates", columnList = "start_date, end_date")
        })
public class TimePeriod {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "time_period_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID timePeriodId;

    @Column(name = "tenant_id", columnDefinition = "UUID", nullable = false)
    private UUID tenantId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private TimePeriodStatus status = TimePeriodStatus.OPEN;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
