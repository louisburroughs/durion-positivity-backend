package com.positivity.platformsender.internal.entity;

import com.positivity.platformsender.internal.enums.MessageChannel;
import com.positivity.platformsender.internal.enums.SentMessageStatus;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One send request (FI-2 §1), keyed for idempotency by the caller's {@code messageId}. Holds the
 * SHA-256 of the address it went to, never the address itself.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "sent_message")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SentMessage extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "sent_message_id", columnDefinition = "UUID")
    private UUID sentMessageId;

    /** The caller's idempotency key ({@code campaignSendId} for pos-marketing). */
    @Column(name = "message_id", nullable = false, updatable = false)
    private UUID messageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 8, updatable = false)
    private MessageChannel channel;

    @Column(name = "recipient_party_id", nullable = false, updatable = false)
    private UUID recipientPartyId;

    @Column(name = "contact_id", updatable = false)
    private UUID contactId;

    @Column(name = "campaign_code", nullable = false, length = 100, updatable = false)
    private String campaignCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SentMessageStatus status;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "address_hash", length = 64)
    private String addressHash;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
