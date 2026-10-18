package com.positivity.inboundmail.internal.entity;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Metadata of one staged attachment (S27 ruling 3). {@link #ref} is the UUIDv7 the email command
 * carries and pos-accounting fetches by; it is stable across redelivery. {@link #fileName} is
 * CONFIDENTIAL text (ADR-0072): never a key, a path or a log value.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "staged_attachment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "fileName")
public class StagedAttachment extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "ref", columnDefinition = "UUID")
    private UUID ref;

    @Column(name = "inbound_message_id", nullable = false, updatable = false)
    private UUID inboundMessageId;

    @Column(name = "file_name", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String fileName;

    @Column(name = "declared_type", nullable = false, updatable = false)
    private String declaredType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, updatable = false, length = 64)
    private String sha256;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "collected_at")
    private Instant collectedAt;
}
