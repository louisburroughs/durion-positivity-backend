package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Local replica of pos-catalog product identity codes (CAP-322 #1312; ADR-0044 R3).
 *
 * <p>Supplier stock hints carry a vendor's EAN and no product id, so resolving one is a read of
 * catalog-owned identity. ADR-0044 R1 forbids calling pos-catalog synchronously to answer it, so
 * the codes travel here as {@code catalog.product.updated} facts and resolution reads this table.
 * pos-supplier keeps the identical replica for PRICAT matching (CAP-318 #1224); each module owns
 * its own copy, and neither reads the other's.
 *
 * <p>Keyed by product because a pos-catalog product carries at most one typed code. A cleared code
 * is persisted as a null {@code code} rather than by deleting the row: "this product has no code"
 * and "this module has never heard of this product" are different answers and the resolver reports
 * them differently.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "ext_product_code",
        indexes = @Index(name = "idx_ext_product_code_lookup", columnList = "code_type, code"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtProductCodeReplica {

    /** pos-catalog's product id, replicated verbatim; never generated here. */
    @Id
    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /** {@code EAN} or {@code UPC}; null when the product carries no code. */
    @Column(name = "code_type", length = 16)
    private String codeType;

    /** Exact code value as pos-catalog holds it; null when the product carries no code. */
    @Column(name = "code", length = 64)
    private String code;

    /** Source fact's aggregateVersion, so a late fact cannot overwrite a newer one. */
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule: the key is pos-catalog's UUIDv7
     * product id, replicated as-is rather than generated in this module.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
