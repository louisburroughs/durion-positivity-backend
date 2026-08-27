package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.SourcingStrategyConfig;
import com.positivity.inventory.internal.enums.SourcingScopeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link SourcingStrategyConfig} rows (odoo-parity H1, issue #1037). */
public interface SourcingStrategyConfigRepository extends JpaRepository<SourcingStrategyConfig, UUID> {

    Optional<SourcingStrategyConfig> findByScopeTypeAndScopeValue(SourcingScopeType scopeType, String scopeValue);

    Optional<SourcingStrategyConfig> findByScopeTypeAndScopeValueIsNull(SourcingScopeType scopeType);

    Optional<SourcingStrategyConfig> findByScopeTypeAndScopeValueAndActiveTrue(
            SourcingScopeType scopeType, String scopeValue);

    Optional<SourcingStrategyConfig> findByScopeTypeAndScopeValueIsNullAndActiveTrue(SourcingScopeType scopeType);

    /**
     * Every active row at one scope kind. Used by the SKU_CATEGORY cut-over audit (#1535): sourcing
     * is the half of that flag that is easy to overlook, and SKU_CATEGORY is its highest-precedence
     * scope.
     */
    List<SourcingStrategyConfig> findByScopeTypeAndActiveTrue(SourcingScopeType scopeType);

    List<SourcingStrategyConfig> findAllByOrderByScopeTypeAscScopeValueAsc();
}
