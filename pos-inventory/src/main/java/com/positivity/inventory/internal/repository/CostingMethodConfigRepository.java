package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.CostingMethodConfig;
import com.positivity.inventory.internal.enums.CostingScopeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link CostingMethodConfig} rows (odoo-parity J1, issue #1048). */
public interface CostingMethodConfigRepository extends JpaRepository<CostingMethodConfig, UUID> {

    Optional<CostingMethodConfig> findByScopeTypeAndScopeValue(CostingScopeType scopeType, String scopeValue);

    Optional<CostingMethodConfig> findByScopeTypeAndScopeValueIsNull(CostingScopeType scopeType);

    Optional<CostingMethodConfig> findByScopeTypeAndScopeValueAndActiveTrue(
            CostingScopeType scopeType, String scopeValue);

    List<CostingMethodConfig> findByScopeTypeAndScopeValueInAndActiveTrue(
            CostingScopeType scopeType, Collection<String> scopeValues);

    Optional<CostingMethodConfig> findByScopeTypeAndScopeValueIsNullAndActiveTrue(CostingScopeType scopeType);

    List<CostingMethodConfig> findAllByOrderByScopeTypeAscScopeValueAsc();
}
