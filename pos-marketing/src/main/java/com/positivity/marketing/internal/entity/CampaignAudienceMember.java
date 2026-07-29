package com.positivity.marketing.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A party the CRM reported as matching a campaign's segment (Story #1148).
 *
 * <p>Materialized from a {@code customer.segment.resolved} fact. Because membership arrives
 * asynchronously, a campaign's audience is a <em>snapshot</em> with a known age rather than a
 * live query — {@code resolvedAt} is surfaced in the preview so a marketer can see how current
 * the numbers are before committing to a send.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "campaign_audience_member",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_campaign_audience_member",
                        columnNames = {"campaign_id", "party_id"}),
        indexes = {@Index(name = "idx_campaign_audience_campaign", columnList = "campaign_id")})
public class CampaignAudienceMember {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "audience_member_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID audienceMemberId;

    @Column(name = "campaign_id", columnDefinition = "UUID", nullable = false)
    private UUID campaignId;

    @Column(name = "party_id", columnDefinition = "UUID", nullable = false)
    private UUID partyId;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt;
}
