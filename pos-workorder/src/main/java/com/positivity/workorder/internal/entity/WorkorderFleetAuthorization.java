package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.workorder.internal.enums.FleetAuthorizationResolution;
import com.positivity.workorder.internal.enums.FleetAuthorizationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A workorder's fleet payment authorization — the payer's permission to do the work (#1346).
 *
 * <h2>Owned here, evaluated elsewhere</h2>
 *
 * pos-supplier talks to the fleet program and reports what it said. This row is workexec's record of
 * that decision, and workexec owns the execution gate and every workorder state transition. The
 * division matters: a supplier result must distinguish granted, refused and pending, and this side
 * must preserve that distinction without reinterpretation.
 *
 * <h2>Why it is not a workorder status</h2>
 *
 * The domain ruled fleet authorization is a separate payer-authorization lifecycle.
 * {@code AWAITING_APPROVAL} keeps its existing meaning — customer consent for added scope during
 * execution — and is untouched. Renaming or overloading an existing state would need a state-machine
 * migration, and would conflate "who is paying" with "how far along is the work".
 *
 * <h2>The row records attempts, not just outcomes</h2>
 *
 * {@link #firstBlockedAt} is set the first time the start gate refuses a start, and the delayed
 * release of held resources is measured from there rather than from the request. A job nobody has
 * tried to start is not holding a bay up, so releasing its assignment would cost capacity to solve a
 * problem that does not exist yet.
 */
@Entity
@Table(name = "workorder_fleet_authorization")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkorderFleetAuthorization {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "workorder_fleet_authorization_id")
    private UUID workorderFleetAuthorizationId;

    @Column(name = "workorder_id", nullable = false)
    private UUID workorderId;

    /** The fleet program asked, from the payer's billing rules. */
    @Column(name = "supplier_ref", nullable = false, length = 100)
    private String supplierRef;

    /** The vendor profile that answered, once a decision names one. */
    @Column(name = "vendor_profile_id")
    private UUID vendorProfileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FleetAuthorizationStatus status;

    /** The vendor's own refusal code, unchanged: it is what an advisor quotes back to a fleet. */
    @Column(name = "vendor_reason_code", length = 50)
    private String vendorReasonCode;

    @Column(name = "vendor_reason_text", length = 1000)
    private String vendorReasonText;

    /** Why no answer could be obtained. Never presented as a vendor decision. */
    @Column(name = "review_reason", length = 1000)
    private String reviewReason;

    @Column(name = "contract_reference", length = 100)
    private String contractReference;

    @Column(name = "authorized_amount", precision = 19, scale = 4)
    private BigDecimal authorizedAmount;

    @Column(name = "currency", length = 3)
    private String currency;

    /** What an advisor decided to do about a refusal. Null while nothing has been decided. */
    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", length = 30)
    private FleetAuthorizationResolution resolution;

    @Column(name = "resolution_reason", length = 1000)
    private String resolutionReason;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "requested_at")
    private Instant requestedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** When the start gate first refused a start. The release delay is measured from here. */
    @Column(name = "first_blocked_at")
    private Instant firstBlockedAt;

    @Column(name = "resources_released_at")
    private Instant resourcesReleasedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", length = 100)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
