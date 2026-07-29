package com.positivity.marketing.internal.entity;

import com.positivity.marketing.internal.enums.AudienceType;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.enums.CampaignStatus;
import com.positivity.marketing.internal.enums.ScheduleType;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A marketing campaign: an audience, a message, a window, and a lifecycle (Story #1146).
 *
 * <p>Cross-domain references ({@code segmentId}, {@code promotionOfferId},
 * {@code catalogFocusRef}) are stored as bare identifiers with no foreign key — those
 * aggregates live in pos-customer, pos-price, and pos-catalog, and ADR-0044 forbids a
 * database-level tie across the wall. They are validated over REST at schedule time instead,
 * which is the moment correctness actually matters.
 *
 * <p>{@code campaignProgramId} groups the commercial and individual arms of one initiative so
 * their results can be compared; it is a free grouping key, not a modelled parent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "campaign",
        indexes = {
            @Index(name = "idx_campaign_status", columnList = "status"),
            @Index(name = "idx_campaign_program", columnList = "campaign_program_id"),
            @Index(name = "idx_campaign_segment", columnList = "segment_id")
        })
public class Campaign {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "campaign_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID campaignId;

    /** Stable human-facing code; the attribution key carried on redemptions. */
    @Column(name = "code", nullable = false, length = 100)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    /** Immutable after creation — see {@link AudienceType}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "audience_type", columnDefinition = "VARCHAR(20)", nullable = false, updatable = false)
    private AudienceType audienceType;

    /** Groups the commercial and individual arms of one initiative for comparison. */
    @Column(name = "campaign_program_id", columnDefinition = "UUID")
    private UUID campaignProgramId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "VARCHAR(20)", nullable = false)
    @Builder.Default
    private CampaignStatus status = CampaignStatus.DRAFT;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "campaign_channel", joinColumns = @JoinColumn(name = "campaign_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 20, nullable = false)
    @Builder.Default
    private Set<CampaignChannel> channels = new HashSet<>();

    /** pos-customer segment id. No FK: cross-service (ADR-0044). */
    @Column(name = "segment_id", columnDefinition = "UUID")
    private UUID segmentId;

    /** pos-price offer id, validated as ACTIVE at schedule time. */
    @Column(name = "promotion_offer_id", columnDefinition = "UUID")
    private UUID promotionOfferId;

    /** pos-catalog reference the campaign highlights, resolved at schedule time. */
    @Column(name = "catalog_focus_ref", length = 200)
    private String catalogFocusRef;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", columnDefinition = "VARCHAR(20)", nullable = false)
    @Builder.Default
    private ScheduleType scheduleType = ScheduleType.IMMEDIATE;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "email_template_id", columnDefinition = "UUID")
    private UUID emailTemplateId;

    @Column(name = "sms_template_id", columnDefinition = "UUID")
    private UUID smsTemplateId;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
