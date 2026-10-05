package com.positivity.securityservice.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The file a completed {@link AuditExportJob} produced (#2408, B-4), stored in the database with
 * the job rather than in object storage. One per job; written once, never updated.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "audit_export_files")
public class AuditExportFile extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "file_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID fileId;

    @Column(name = "job_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID jobId;

    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100, updatable = false)
    private String contentType;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String content;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
