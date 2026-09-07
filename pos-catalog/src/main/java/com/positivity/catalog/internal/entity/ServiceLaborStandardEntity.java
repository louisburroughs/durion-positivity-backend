package com.positivity.catalog.internal.entity;

import com.positivity.catalog.internal.enums.LaborStandardOwnerScope;
import com.positivity.catalog.internal.enums.LaborTimeType;
import com.positivity.shared.id.UUIDv7Id;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One vehicle-specific published labor time for a service operation, with provenance
 * (#1569, sourcing plan §4.2).
 *
 * <p>Rows are append-preferred: corrections and new feed revisions supersede the old row
 * ({@code supersededAt} set, replacement inserted) rather than updating in place, so a quote
 * made against revision N stays explainable after revision N+1 lands. A null vehicle-key
 * field is a wildcard, matching the pos-vehicle-fitment convention.
 *
 * <p>No JPA relation to {@link ServiceEntity} — the standard is looked up by {@code serviceId}
 * on its own paths and never navigated from the service aggregate; the FK lives in V18 for
 * integrity only.
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "service_labor_standard")
public class ServiceLaborStandardEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    // ── Vehicle key (null = wildcard) ───────────────────────────────────────────────────

    /** Single year or a range like {@code 2019-2023}, matching PartFitmentEntity convention. */
    @Column(name = "vehicle_year")
    private String vehicleYear;

    private String make;
    private String model;
    private String submodel;

    @Column(name = "engine_code")
    private String engineCode;

    /** Populated only once a licensed source supplies ACES vehicle ids. */
    @Column(name = "aces_vehicle_id")
    private Long acesVehicleId;

    // ── The time ────────────────────────────────────────────────────────────────────────

    /** Decimal hours in tenths (0.1 hr = 6 min) — never minutes, never seconds. */
    @Column(name = "labor_hours", nullable = false, precision = 5, scale = 1)
    private BigDecimal laborHours;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_type", nullable = false)
    private LaborTimeType timeType;

    // ── Ownership (Tier 0: a shop's own number beats a published one) ──────────────────

    /** {@code PLATFORM} for guide and platform-authored rows; {@code SHOP} for a shop's own. */
    @Enumerated(EnumType.STRING)
    @Column(name = "owner_scope", nullable = false)
    private LaborStandardOwnerScope ownerScope = LaborStandardOwnerScope.PLATFORM;

    /** Required when {@link #ownerScope} is {@code SHOP}, null otherwise (V21 CHECK). */
    @Column(name = "owner_location_id")
    private UUID ownerLocationId;

    // ── Relationships that make workorder summation honest ────────────────────────────

    /** Lines sharing a group share setup time; a workorder total must not double-bill it. */
    @Column(name = "overlap_group")
    private String overlapGroup;

    /** Operation codes whose time is already included in this one's hours. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "included_op_codes")
    private List<String> includedOpCodes;

    // ── Provenance ("defensible on an invoice") ────────────────────────────────────────

    /** {@code DURION} for hand-authored rows; a provider source code for imported ones. */
    @Column(name = "source_code", nullable = false)
    private String sourceCode;

    @Column(name = "source_revision", nullable = false)
    private String sourceRevision;

    @Column(name = "published_at")
    private LocalDate publishedAt;

    /** Ties an imported row to its chunked-manifest import run; null for authored rows. */
    @Column(name = "import_manifest_id")
    private UUID importManifestId;

    /** Set when a newer revision or correction replaced this row; active rows carry null. */
    @Column(name = "superseded_at")
    private Instant supersededAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
