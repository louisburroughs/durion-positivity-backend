package com.positivity.inboundmail.internal.entity;

import com.positivity.inboundmail.internal.enums.MessageStatus;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import lombok.ToString;

/**
 * One message the edge has seen for a tenant: the idempotency key, the verdict and the outcome. Holds
 * no body. {@link #fromAddress} is CONFIDENTIAL (ADR-0072 Decision 1), so it stays out of {@link
 * #toString()}.
 */
@Entity
@Table(name = "inbound_message")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"fromAddress", "messageKey"})
public class InboundMessage extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "inbound_message_id", columnDefinition = "UUID")
    private UUID inboundMessageId;

    @Column(name = "address_id", nullable = false, updatable = false)
    private UUID addressId;

    /** The message's {@code Message-ID}, or the sha256 of the raw message when it has none; unique per tenant. */
    @Column(name = "message_key", nullable = false, updatable = false, length = 1024)
    private String messageKey;

    @Column(name = "ses_message_id", updatable = false, length = 128)
    private String sesMessageId;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "from_address", length = 320)
    private String fromAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MessageStatus status;

    @Column(name = "reason", length = 64)
    private String reason;

    @Column(name = "rate_limit_window_id")
    private UUID rateLimitWindowId;
}
