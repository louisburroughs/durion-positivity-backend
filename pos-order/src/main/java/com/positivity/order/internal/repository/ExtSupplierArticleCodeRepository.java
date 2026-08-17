package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtSupplierArticleCode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtSupplierArticleCodeRepository extends JpaRepository<ExtSupplierArticleCode, UUID> {

    Optional<ExtSupplierArticleCode> findBySupplierRefAndProductId(String supplierRef, UUID productId);
}
