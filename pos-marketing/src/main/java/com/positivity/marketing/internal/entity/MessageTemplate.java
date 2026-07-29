package com.positivity.marketing.internal.entity;

import com.positivity.marketing.internal.enums.AudienceType;
import com.positivity.marketing.internal.enums.CampaignChannel;
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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A channel-specific message body with substitution tokens (Story #1147).
 *
 * <p>{@code audienceType} is part of the template, not just the campaign, because the two
 * audiences have different token vocabularies: {@code accountName} means nothing for an
 * individual and {@code vehicleMake} rarely means one thing for a fleet. Binding the template
 * to an audience lets unknown tokens be rejected at save time rather than rendering as blanks
 * in front of a customer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "message_template",
        indexes = {@Index(name = "idx_message_template_channel", columnList = "channel, audience_type")})
public class MessageTemplate {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "template_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID templateId;

    /** Unique case-insensitively via the {@code uq_message_template_name} index in V3. */
    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", columnDefinition = "VARCHAR(20)", nullable = false)
    private CampaignChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience_type", columnDefinition = "VARCHAR(20)", nullable = false)
    private AudienceType audienceType;

    /** Email only; SMS has no subject line. */
    @Column(name = "subject", length = 255)
    private String subject;

    @Column(name = "body", columnDefinition = "text", nullable = false)
    private String body;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
