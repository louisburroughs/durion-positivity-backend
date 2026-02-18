package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.PriceBookEntity;
import com.positivity.catalog.internal.entity.PriceBookScope;
import com.positivity.catalog.internal.entity.PriceBookStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceBookRepository extends JpaRepository<PriceBookEntity, UUID> {

    Optional<PriceBookEntity> findByScopeAndScopeIdAndStatus(PriceBookScope scope, UUID scopeId,
            PriceBookStatus status);

    Optional<PriceBookEntity> findByScopeAndIsDefaultTrueAndStatus(PriceBookScope scope, PriceBookStatus status);

    List<PriceBookEntity> findByStatus(PriceBookStatus status);
}
