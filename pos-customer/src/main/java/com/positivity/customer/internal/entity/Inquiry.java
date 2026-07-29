package com.positivity.customer.internal.entity;

import com.positivity.customer.internal.enums.AudienceType;
import com.positivity.customer.internal.enums.InquiryChannel;
import com.positivity.customer.internal.enums.InquiryStatus;
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
 * An inbound request for service or a quote, before it is anyone's customer (Story #1154).
 *
 * <p>Contact details are captured <em>flat</em> on this row rather than being pushed into
 * pos-people-contact immediately. An inquiry is unverified, often duplicated, and frequently
 * abandoned; creating a canonical person for every one would fill the identity store with
 * junk. Identity is created only at conversion, which is also where duplicate detection runs.
 *
 * <p>Lives in pos-customer, not {@code pos-inquiry} — that module is supplier-scoped
 * (decision O-6).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "inquiry",
        indexes = {
            @Index(name = "idx_inquiry_status", columnList = "status, created_at"),
            @Index(name = "idx_inquiry_party", columnList = "party_id"),
            @Index(name = "idx_inquiry_campaign", columnList = "campaign_code")
        })
public class Inquiry {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "inquiry_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID inquiryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", columnDefinition = "VARCHAR(20)", nullable = false)
    private InquiryChannel channel;

    /** Whether this is a fleet enquiry or an individual — drives which party type conversion creates. */
    @Enumerated(EnumType.STRING)
    @Column(name = "audience_type", columnDefinition = "VARCHAR(20)", nullable = false)
    private AudienceType audienceType;

    @Column(name = "contact_name", nullable = false, length = 200)
    private String contactName;

    /** Organization name, for a commercial enquiry. */
    @Column(name = "organization_name", length = 200)
    private String organizationName;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "vehicle_of_interest", length = 300)
    private String vehicleOfInterest;

    @Column(name = "service_of_interest", length = 300)
    private String serviceOfInterest;

    @Column(name = "message", length = 2000)
    private String message;

    /** Campaign that drove the enquiry, so acquisition can be attributed (Story #1142). */
    @Column(name = "campaign_code", length = 100)
    private String campaignCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "VARCHAR(20)", nullable = false)
    @Builder.Default
    private InquiryStatus status = InquiryStatus.NEW;

    /** Set at conversion; the party this enquiry became. */
    @Column(name = "party_id", columnDefinition = "UUID")
    private UUID partyId;

    @Column(name = "assigned_to", length = 255)
    private String assignedTo;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    /**
     * Truncated source address of a public submission, retained only for abuse triage. Never a
     * full address — a public form must not build a precise visitor log as a side effect.
     */
    @Column(name = "submitted_from", length = 64)
    private String submittedFrom;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
