package com.positivity.customer.internal.entity;

import com.positivity.customer.internal.enums.InteractionDirection;
import com.positivity.customer.internal.enums.InteractionType;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One recorded touch on a party's timeline (Story #1141).
 *
 * <p>Generalizes the one-way {@code PartyNote} workorder projection so campaign sends,
 * CSR calls, follow-ups, and workorder notes share a single queryable history. Without this,
 * "what have we said to this customer lately?" needs a join across every feature that ever
 * contacted them.
 *
 * <p>{@code sourceEventId} carries the originating envelope id for event-fed rows, which is
 * what makes ingestion idempotent — a replayed campaign-send fact updates nothing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "customer_interaction",
        indexes = {
            @Index(name = "idx_customer_interaction_party", columnList = "party_id, occurred_at"),
            @Index(name = "idx_customer_interaction_source_event", columnList = "source_event_id"),
            @Index(name = "idx_customer_interaction_campaign", columnList = "campaign_id")
        })
public class CustomerInteraction {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "interaction_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID interactionId;

    @Column(name = "party_id", columnDefinition = "UUID", nullable = false)
    private UUID partyId;

    /** The specific contact reached, when the touch targeted one rather than the account. */
    @Column(name = "contact_id", columnDefinition = "UUID")
    private UUID contactId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", columnDefinition = "VARCHAR(30)", nullable = false)
    private InteractionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", columnDefinition = "VARCHAR(20)")
    private MarketingChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", columnDefinition = "VARCHAR(20)", nullable = false)
    @Builder.Default
    private InteractionDirection direction = InteractionDirection.OUTBOUND;

    /** Campaign this touch belongs to; owned by pos-marketing, stored here as a bare id. */
    @Column(name = "campaign_id", columnDefinition = "UUID")
    private UUID campaignId;

    @Column(name = "campaign_code", length = 100)
    private String campaignCode;

    @Column(name = "subject", length = 255)
    private String subject;

    @Column(name = "summary", length = 1000)
    private String summary;

    /** Free text; redacted on read per DECISION-INVENTORY-006 before it leaves the service. */
    @Column(name = "body", length = 4000)
    private String body;

    @Column(name = "actor", length = 255)
    private String actor;

    @Column(name = "source_workorder_id", length = 64)
    private String sourceWorkorderId;

    /** Originating event envelope id for event-fed rows; the ingestion idempotency key. */
    @Column(name = "source_event_id", length = 64)
    private String sourceEventId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
