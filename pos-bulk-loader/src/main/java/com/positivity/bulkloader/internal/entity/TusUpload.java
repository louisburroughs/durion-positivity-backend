package com.positivity.bulkloader.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "tus_upload")
@Getter
@Setter
@NoArgsConstructor
public class TusUpload {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "job_id", updatable = false, nullable = false)
    private UUID jobId;

    @Column(name = "operator_id", updatable = false, nullable = false)
    private String operatorId;

    @Column(name = "file_name", updatable = false, nullable = false)
    private String fileName;

    @Column(name = "total_size", updatable = false, nullable = false)
    private long totalSize;

    @Column(name = "upload_offset", nullable = false)
    private long uploadOffset;

    @Column(name = "completed", nullable = false)
    private boolean completed;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
