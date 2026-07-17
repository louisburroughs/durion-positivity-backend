package com.positivity.warranty.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.warranty.internal.enums.ClaimDecision;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.enums.ClaimType;
import com.positivity.warranty.internal.enums.EligibilityResult;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The warranty-claim aggregate root (PRD §3.4). Vehicle facts ({@code vin},
 * {@code odometerAtClaim}) are snapshots read from pos-vehicle-inventory at intake and frozen,
 * so the claim stays self-explanatory even if upstream data changes. Claim lines are part of
 * the aggregate (cascade + orphan removal); settlements, reimbursements, part returns, status
 * history and notes are separate entities keyed by {@code claimId}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "warranty_claim",
        indexes = {
            @Index(name = "idx_wclaim_customer", columnList = "customer_id"),
            @Index(name = "idx_wclaim_vehicle", columnList = "vehicle_id"),
            @Index(name = "idx_wclaim_status", columnList = "status"),
            @Index(name = "idx_wclaim_location", columnList = "location_id")
        })
@EntityListeners(AuditingEntityListener.class)
public class WarrantyClaim {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Business identifier {@code WC-yyyy-nnnnnn} (PRD §4), allocated by ClaimCodeService. */
    @Column(name = "claim_code", nullable = false, unique = true, length = 20)
    private String claimCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_type", nullable = false, length = 32)
    private ClaimType claimType;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ClaimStatus status = ClaimStatus.DRAFT;

    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    /** VIN snapshot frozen at intake. */
    @Column(name = "vin", length = 17)
    private String vin;

    /** Odometer snapshot frozen at intake. */
    @Column(name = "odometer_at_claim")
    private Long odometerAtClaim;

    /** Odometer unit snapshot (e.g. MILES/KILOMETERS) as reported by pos-vehicle-inventory. */
    @Column(name = "odometer_unit", length = 16)
    private String odometerUnit;

    @Column(name = "origin_workorder_id")
    private UUID originWorkorderId;

    @Column(name = "origin_invoice_id")
    private UUID originInvoiceId;

    @Column(name = "origin_sale_date")
    private LocalDate originSaleDate;

    /** True for walk-ins whose original sale could not be located (manual origin entry). */
    @Builder.Default
    @Column(name = "origin_unverified", nullable = false)
    private Boolean originUnverified = false;

    @Column(name = "provider_id")
    private UUID providerId;

    @Column(name = "policy_id")
    private UUID policyId;

    @Column(name = "registration_id")
    private UUID registrationId;

    @Column(name = "failure_description", columnDefinition = "text")
    private String failureDescription;

    @Column(name = "failure_date")
    private LocalDate failureDate;

    /** JSONB array of photo URL strings (URL-reference pattern, PRD §1). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "photo_evidence_urls", columnDefinition = "jsonb")
    private List<String> photoEvidenceUrls;

    @Enumerated(EnumType.STRING)
    @Column(name = "eligibility_result", length = 32)
    private EligibilityResult eligibilityResult;

    /** Per-term pass/fail list produced by the eligibility evaluation (PRD §6), frozen as JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "eligibility_reasons", columnDefinition = "jsonb")
    private List<Map<String, Object>> eligibilityReasons;

    /** Computed suggestion (outcome + amounts) pre-filled for staff (PRD §6), frozen as JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suggested_outcome", columnDefinition = "jsonb")
    private Map<String, Object> suggestedOutcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 32)
    private ClaimDecision decision;

    @Column(name = "decision_reason", columnDefinition = "text")
    private String decisionReason;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** True when the human decision contradicts the computed suggestion (reason required). */
    @Builder.Default
    @Column(name = "overrode_suggestion", nullable = false)
    private Boolean overrodeSuggestion = false;

    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WarrantyClaimLine> lines = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Maintains both sides of the claim ↔ line association. */
    public void addLine(WarrantyClaimLine line) {
        lines.add(line);
        line.setClaim(this);
    }

    /** Maintains both sides of the claim ↔ line association (orphan removal deletes the row). */
    public void removeLine(WarrantyClaimLine line) {
        lines.remove(line);
        line.setClaim(null);
    }
}
