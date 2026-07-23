package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.PurchaseSuggestionStatus;
import com.positivity.inventory.internal.enums.SuggestedVendorType;
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
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A scan-generated advice record to purchase stock for a replenishment policy whose
 * preferred source is PURCHASE (odoo-parity F4, issue #1044; spec F4, plan D-3).
 *
 * <p>Lifecycle {@code SUGGESTED → ACCEPTED → CONVERTED | DISMISSED}; the ACCEPT step is
 * human-mandatory (D-3) and conversion creates a DRAFT purchase order through the existing
 * PO service, so the standard PO approval workflow still applies afterwards.
 *
 * <p>Vendor fields are nullable: a suggestion is advice, not a commitment, so a policy with
 * no usable vendor-feed data still produces a (vendor-less, non-convertible) suggestion
 * whose {@code selectionReason} explains the gap. {@code unitCostMinor} is nullable because
 * distributor feeds carry no price.
 *
 * <p>Open (SUGGESTED/ACCEPTED) suggestion quantities count as in-progress supply in the
 * replenishment netting sum (the F2 seam in
 * {@code ReplenishmentServiceImpl#inProgressQuantity}); a CONVERTED suggestion keeps
 * netting only while its DRAFT PO has not yet entered the expected-supply sum (POs count
 * from APPROVED) — see the netting handoff documented there.
 */
@Entity
@Table(name = "purchase_suggestion")
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseSuggestion {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID suggestionId;

    /** The replenishment policy this suggestion was computed for (one open suggestion per policy). */
    @Column(nullable = false)
    private UUID policyId;

    /** Stock item the policy governs (mirrors {@code ReplenishmentPolicy.itemSKU}). */
    @Column(nullable = false)
    private String itemSKU;

    /** Destination (policy) location the purchased stock is for. */
    @Column(nullable = false)
    private UUID locationId;

    /**
     * Quantity to purchase AFTER rounding: {@code max(minOrderQty,
     * roundUpToMultiple(need, max(orderMultiple, packSize)))} — see
     * {@code PurchaseSuggestionCreationService#roundedSuggestionQuantity}.
     */
    @Column(nullable = false)
    private Integer suggestedQuantity;

    /** Which normalized feed the vendor came from; {@code null} when no vendor was selectable. */
    @Enumerated(EnumType.STRING)
    private SuggestedVendorType suggestedVendorType;

    /** Manufacturer or distributor UUID; {@code null} when no vendor was selectable. */
    private UUID vendorRefId;

    /** Per-base-unit cost in minor units from the feed price; {@code null} when the feed lacks a price. */
    private Long unitCostMinor;

    /** ISO 4217 currency of {@code unitCostMinor}; {@code null} when the feed lacks a price. */
    private String unitCostCurrency;

    /**
     * Selected vendor's pack size at suggestion time — kept so conversion can express the PO
     * line in the vendor's pack UoM (B2 documentUom rule) deterministically, without
     * re-reading feed data that may have changed since the suggestion was computed.
     */
    private Integer vendorPackSize;

    /** Lead-time estimate in days (vendor MAX-day estimate, consistent with F2's horizon math). */
    private Integer leadTimeDays;

    /** Suggestion date + {@code leadTimeDays}: earliest date the purchase could plausibly arrive. */
    private LocalDate earliestExpectedDate;

    /** Explainable record of the deterministic vendor ranking inputs (spec F4). */
    @Column(nullable = false, length = 2000)
    private String selectionReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PurchaseSuggestionStatus status;

    /** Mandatory human-entered reason captured on DISMISSED suggestions. */
    private String dismissalReason;

    /** The DRAFT purchase order this suggestion was converted into; set with status CONVERTED. */
    private UUID convertedPurchaseOrderId;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
