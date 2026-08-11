package com.positivity.customer.internal.entity;

import com.positivity.customer.internal.enums.ConsentChangeSource;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.enums.MarketingConsent;
import com.positivity.customer.internal.enums.OptOutReason;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Append-only record of a marketing-consent change (Story #1139).
 *
 * <p>Deliberately has no setters and no {@code @LastModifiedDate}: a consent trail that
 * can be edited is worthless as compliance evidence. The service layer only ever inserts,
 * and there is no update or delete path anywhere in the module.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "consent_event",
        indexes = {
            @Index(name = "idx_consent_event_party", columnList = "party_id, occurred_at"),
            @Index(name = "idx_consent_event_occurred", columnList = "occurred_at")
        })
public class ConsentEvent {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "consent_event_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID consentEventId;

    @Column(name = "party_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID partyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", columnDefinition = "VARCHAR(20)", nullable = false, updatable = false)
    private MarketingChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_value", columnDefinition = "VARCHAR(20)", nullable = false, updatable = false)
    private MarketingConsent oldValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_value", columnDefinition = "VARCHAR(20)", nullable = false, updatable = false)
    private MarketingConsent newValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", columnDefinition = "VARCHAR(30)", updatable = false)
    private OptOutReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", columnDefinition = "VARCHAR(30)", nullable = false, updatable = false)
    private ConsentChangeSource source;

    @Column(name = "actor", length = 255, updatable = false)
    private String actor;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
}
