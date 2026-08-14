package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.SupplierStockHint;
import com.positivity.inventory.internal.enums.SupplierHintIdentityKind;
import com.positivity.inventory.internal.enums.SupplierHintResolutionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Supplier availability hints (CAP-322, #1312).
 *
 * <p>Only the supplier-hint classes may depend on this repository — an ArchUnit rule enforces it —
 * so that no valuation or on-hand ATP path can come to read a vendor's stock as if it were ours.
 */
public interface SupplierStockHintRepository extends JpaRepository<SupplierStockHint, UUID> {

    /** The row a snapshot line supersedes, if this vendor has reported the article before. */
    Optional<SupplierStockHint> findByVendorProfileIdAndIdentityKindAndIdentityValue(
            UUID vendorProfileId, SupplierHintIdentityKind identityKind, String identityValue);

    /** Read path: every vendor's hint for a resolved product, newest fetch first. */
    List<SupplierStockHint> findByResolvedProductIdOrderByFetchedAtDesc(UUID resolvedProductId);

    /** Read path: hints for an EAN, whichever identifier was elected as the row's identity. */
    List<SupplierStockHint> findByArticleEanOrderByFetchedAtDesc(String articleEan);

    /** Read path: hints for our own article code as vendors hold it. */
    List<SupplierStockHint> findByBuyersArticleIdOrderByFetchedAtDesc(String buyersArticleId);

    /** Read path: hints for a vendor's own article code. */
    List<SupplierStockHint> findBySupplierArticleCodeOrderByFetchedAtDesc(String supplierArticleCode);

    /** Resolver sweep: the oldest slice of the backlog, bounded so one pass cannot run away. */
    List<SupplierStockHint> findByResolutionStatusOrderByFetchedAtAsc(
            SupplierHintResolutionStatus resolutionStatus, Limit limit);
}
