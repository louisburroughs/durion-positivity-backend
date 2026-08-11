package com.positivity.marketing.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A redemption credited to a campaign (Story #1152).
 *
 * <p>The primary key is the CRM's {@code redemptionId}, not a locally generated id. That is
 * the whole anti-double-count mechanism: {@code customer.events.v1} is at-least-once, so the
 * same redemption fact will be delivered more than once, and a generated key would turn each
 * replay into another conversion.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "campaign_attribution",
        indexes = {@Index(name = "idx_campaign_attribution_campaign", columnList = "campaign_id")})
public class CampaignAttribution {

    @Id
    @Column(name = "redemption_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID redemptionId;

    @Column(name = "campaign_id", columnDefinition = "UUID", nullable = false)
    private UUID campaignId;

    @Column(name = "campaign_code", nullable = false, length = 100)
    private String campaignCode;

    @Column(name = "party_id", columnDefinition = "UUID", nullable = false)
    private UUID partyId;

    @Column(name = "workorder_id", columnDefinition = "UUID")
    private UUID workorderId;

    @Column(name = "discount_amount", precision = 19, scale = 4)
    private BigDecimal discountAmount;

    @Column(name = "attributed_at", nullable = false)
    private Instant attributedAt;

    /** Explicit dependency hooks for the ArchUnit UUIDv7 rules; the id is the owner's redemptionId. */
    @Transient
    public Class<?>[] uuidv7Dependency() {
        return new Class<?>[] {UUIDv7Id.class, UUIDv7Generator.class};
    }
}
