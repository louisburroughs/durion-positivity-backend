package com.positivity.bulkloader.internal.entity;

import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.enums.JobStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "bulk_load_job")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class BulkLoadJob {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "operator_id", nullable = false)
    private String operatorId;

    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "original_file_path")
    private String originalFilePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "domain_type", nullable = false)
    private DomainType domainType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private JobStatus status;

    @Column(name = "total_rows")
    private Long totalRows;

    @Column(name = "processed_rows", nullable = false)
    private Long processedRows = 0L;

    @Column(name = "success_count", nullable = false)
    private Long successCount = 0L;

    @Column(name = "failure_count", nullable = false)
    private Long failureCount = 0L;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (this.status == null) {
            this.status = JobStatus.CREATED;
        }
    }
}
