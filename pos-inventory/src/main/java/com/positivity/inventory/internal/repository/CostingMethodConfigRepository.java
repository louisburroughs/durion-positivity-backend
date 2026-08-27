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

    /**
     * Every active row at one scope kind. Used by the SKU_CATEGORY cut-over audit (#1535) to
     * enumerate the category overrides that would start resolving.
     */
    List<CostingMethodConfig> findByScopeTypeAndActiveTrue(CostingScopeType scopeType);

    /**
     * How many active rows exist at one scope kind, without loading them. The boot-time cut-over
     * notice (#1535) needs only this number when the flag is off, and must not pay for the full
     * impact report to get it.
     */
    long countByScopeTypeAndActiveTrue(CostingScopeType scopeType);

    List<CostingMethodConfig> findAllByOrderByScopeTypeAscScopeValueAsc();
}
