package com.positivity.catalog.internal.entity;

import com.positivity.catalog.internal.enums.LaborStandardOwnerScope;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Data;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A named set of operations a shop sells together (#1575 Tier 0, T0-4).
 *
 * <p>A four-tyre install with the balance and TPMS reset; a fleet PM interval; a seasonal
 * changeover. With {@link #fleetPartyId} set it is one account's <em>requirement</em> set —
 * the same shape scoped to a fleet, because a fleet requirement is a package that belongs to
 * one customer, and a parallel table would duplicate the membership shape and every query over
 * it.
 *
 * <p>{@link #packageLaborHours} is authored, never derived (spec D4): the overlap arithmetic
 * that turns member times into a total lives in pos-workorder, and re-implementing it here would
 * create a second answer to one question. A shop also prices a package as a number it chose, not
 * as a rollup of its parts.
 *
 * <p>No JPA relation to members — they are read through their own repository on the paths that
 * need them, and eager traversal from the package aggregate is exactly the fetch a listing
 * endpoint must not do.
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "service_package")
public class ServicePackageEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    /** Stable, human-meaningful identity, unique across the platform. */
    @Column(name = "package_code", nullable = false, length = 64)
    private String packageCode;

    @Column(nullable = false)
    private String name;

    @Nullable
    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_scope", nullable = false)
    private LaborStandardOwnerScope ownerScope = LaborStandardOwnerScope.PLATFORM;

    /** Required when {@link #ownerScope} is {@code SHOP}, null otherwise (V23 CHECK). */
    @Nullable
    @Column(name = "owner_location_id")
    private UUID ownerLocationId;

    /**
     * Set = this package is one fleet account's requirement set. A bare UUID rather than a
     * relation: the party is mastered in pos-customer, and there are no cross-service foreign
     * keys.
     */
    @Nullable
    @Column(name = "fleet_party_id")
    private UUID fleetPartyId;

    /** Authored decimal hours in tenths for the package as sold; null = priced per member. */
    @Nullable
    @Column(name = "package_labor_hours", precision = 5, scale = 1)
    private BigDecimal packageLaborHours;

    @Column(nullable = false)
    private boolean active = true;

    @Nullable
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Nullable
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Version
    @Column(nullable = false)
    private long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
