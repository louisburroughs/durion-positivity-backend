package com.positivity.securityservice.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The one endpoint a tenant's {@code WEBHOOK} audit exports are delivered to (#2497). At most one row
 * per tenant ({@code UNIQUE (tenant_id)}): registering again replaces the URL and rotates the signing
 * secret on the same row, and deleting removes it, so a later registration gets a new id.
 *
 * <p>No {@code toString}, {@code equals} or {@code hashCode} is generated, so the signing secret
 * never reaches a log line through this entity.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "audit_export_webhook")
public class AuditExportWebhook extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "webhook_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID webhookId;

    /** Absolute {@code https} URL, validated by {@code WebhookDestinationPolicy} when registered. */
    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    /**
     * CONFIDENTIAL. The HMAC-SHA256 key that signs every delivery (32 random bytes, base64url without
     * padding), shown to the registering caller once and never returned again. Stored as-is in this
     * row-level-security protected table; encryption at rest is a follow-up (README). Never log it.
     */
    @Column(name = "signing_secret", nullable = false, length = 128)
    private String signingSecret;

    @Column(name = "registered_by", nullable = false, length = 255)
    private String registeredBy;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
