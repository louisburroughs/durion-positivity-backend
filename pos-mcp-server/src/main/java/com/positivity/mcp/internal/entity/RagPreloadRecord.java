package com.positivity.mcp.internal.entity;

import com.positivity.mcp.internal.enums.RagPreloadStatus;
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
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "mcp_rag_preload_record")
public class RagPreloadRecord {

  @Id
  @GeneratedValue
  @UUIDv7Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "document_id", nullable = false, length = 120)
  private String documentId;

  @Column(name = "content_hash", nullable = false, length = 64)
  private String contentHash;

  @Column(name = "source_path", nullable = false, length = 255)
  private String sourcePath;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private RagPreloadStatus status;

  @Nullable
  @Column(name = "job_id", nullable = true, updatable = false)
  private UUID jobId;

  @Column(name = "loaded_at", nullable = false)
  @CreatedDate
  private OffsetDateTime loadedAt;

  // This is an immutable audit record — one row is written per preload event and
  // never updated.
  // updatedAt is intentionally absent: status transitions create new rows rather
  // than patching existing ones.
}