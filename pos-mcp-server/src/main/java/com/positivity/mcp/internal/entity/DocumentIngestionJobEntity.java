package com.positivity.mcp.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "mcp_document_ingestion_job")
public class DocumentIngestionJobEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false)
    private String documentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DocumentIngestionJobState status;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String metadataJson;

    @Column
    private Integer chunkCount;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column
    private OffsetDateTime startedAt;

    @Column
    private OffsetDateTime completedAt;

    @CreatedDate
    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private OffsetDateTime updatedAt;
}
