package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtSupplierArticleCode;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtSupplierArticleCodeRepository extends JpaRepository<ExtSupplierArticleCode, UUID> {

    Optional<ExtSupplierArticleCode> findBySupplierRefAndProductId(String supplierRef, UUID productId);

    /** Bulk lookup for transmission (CAP-320 #1347), so resolving a whole order's lines is one query. */
    List<ExtSupplierArticleCode> findBySupplierRefAndProductIdIn(String supplierRef, Collection<UUID> productIds);
}
