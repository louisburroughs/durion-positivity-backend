package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Read-only replica of pos-catalog product identity codes, fed by {@code catalog.product.updated}
 * facts on {@code catalog.events.v1} (ADR-0044 R3).
 *
 * <p>This exists because PRICAT matching is a cross-domain <em>read</em>, and ADR-0044 R1 forbids
 * the synchronous call that would otherwise answer it: a domain module resolves another domain's
 * reference data from a local replica, not by calling the owner. pos-catalog owns these codes;
 * nothing in this module writes this table except the event consumer.
 *
 * <p>One row per product, because a pos-catalog product carries at most one typed code. A product
 * whose code is cleared keeps its row with null {@link #code}, so the replica can distinguish
 * "product has no code" from "we have never heard of this product".
 *
 * <p>{@code aggregateVersion} carries the product's {@code updatedAt} epoch millis (pos-catalog has
 * no JPA {@code @Version}); the consumer skips only strictly-lower versions, matching the guard the
 * other catalog consumers in this platform use.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "ext_product_code",
        indexes = {@Index(name = "idx_ext_product_code_lookup", columnList = "code_type, code")})
public class ExtProductCodeReplica {

    @Id
    @AssignedIdentifier("pos-catalog's product id; this table is a replica, so minting an id here"
            + " would create a second identity for a product that already has one")
    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /** {@code EAN} or {@code UPC}; null exactly when {@link #code} is null. */
    @Column(name = "code_type", length = 16)
    private String codeType;

    /** The code as pos-catalog stores it; matching is exact against this value (ADR-0053 §5). */
    @Column(name = "code", length = 64)
    private String code;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
