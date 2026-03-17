package com.positivity.securityservice.internal.entity;

import com.positivity.securityservice.internal.enums.SelfRegistrationAttemptStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "self_registration_attempts")
@Getter
@Setter
public class SelfRegistrationAttempt {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 128)
    private String requestFingerprint;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "username", length = 255)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SelfRegistrationAttemptStatus status;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "person_id")
    private UUID personId;

    @Column(name = "link_status", length = 50)
    private String linkStatus;

    @Column(name = "matched_existing_person", nullable = false)
    private boolean matchedExistingPerson;

    @Column(name = "issued_tokens", nullable = false)
    private boolean issuedTokens;

    @Column(name = "crm_candidate_count")
    private Integer crmCandidateCount;

    @Column(name = "crm_any_matches")
    private Boolean crmAnyMatches;

    @Column(name = "crm_individual_customer_candidate_count")
    private Integer crmIndividualCustomerCandidateCount;

    @Column(name = "crm_commercial_contact_candidate_count")
    private Integer crmCommercialContactCandidateCount;

    @Column(name = "crm_shared_identity_candidate_count")
    private Integer crmSharedIdentityCandidateCount;

    @Column(name = "crm_exact_email_match")
    private Boolean crmExactEmailMatch;

    @Column(name = "crm_exact_phone_match")
    private Boolean crmExactPhoneMatch;

    @Column(name = "crm_exact_name_match")
    private Boolean crmExactNameMatch;

    @Column(name = "crm_review_required")
    private Boolean crmReviewRequired;

    @Column(name = "conflict_code", length = 100)
    private String conflictCode;

    @Column(name = "conflict_message", length = 1000)
    private String conflictMessage;

    @Column(name = "reference_id")
    private UUID referenceId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
