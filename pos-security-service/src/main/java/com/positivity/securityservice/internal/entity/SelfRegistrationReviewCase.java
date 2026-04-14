package com.positivity.securityservice.internal.entity;

import com.positivity.securityservice.internal.enums.SelfRegistrationCaseStatus;
import com.positivity.securityservice.internal.enums.SelfRegistrationCaseType;
import com.positivity.shared.id.UUIDv7Id;
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
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "self_registration_review_cases")
@Getter
@Setter
public class SelfRegistrationReviewCase {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "case_type", nullable = false, length = 40)
    private SelfRegistrationCaseType caseType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SelfRegistrationCaseStatus status;

    @Column(name = "reason_code", nullable = false, length = 100)
    private String reasonCode;

    @Column(name = "reason_message", nullable = false, length = 1000)
    private String reasonMessage;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "requested_username", length = 255)
    private String requestedUsername;

    @Column(name = "person_id")
    private UUID personId;

    @Column(name = "linked_user_id")
    private UUID linkedUserId;

    @Column(name = "crm_candidate_count")
    private Integer crmCandidateCount;

    @Column(name = "crm_shared_identity_candidate_count")
    private Integer crmSharedIdentityCandidateCount;

    @Column(name = "crm_exact_email_match")
    private Boolean crmExactEmailMatch;

    @Column(name = "crm_exact_phone_match")
    private Boolean crmExactPhoneMatch;

    @Column(name = "crm_exact_name_match")
    private Boolean crmExactNameMatch;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by", length = 255)
    private String resolvedBy;

    @Column(name = "resolution_notes", length = 1000)
    private String resolutionNotes;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
