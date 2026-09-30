package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.SkuCostState;
import com.positivity.tenancy.TenantAudited;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for per-SKU {@link SkuCostState} running cost rows (odoo-parity J1, issue #1048). */
public interface SkuCostStateRepository extends JpaRepository<SkuCostState, UUID> {

    Optional<SkuCostState> findByStockItemId(String stockItemId);

    List<SkuCostState> findByStockItemIdIn(Collection<String> stockItemIds);

    /**
     * Insert-if-absent of the empty (uncosted, zero on-hand) cost-state row for a SKU, run inside the caller's
     * transaction (one connection, no {@code REQUIRES_NEW}). Target-less {@code ON CONFLICT DO NOTHING} arbitrates
     * {@code uq_sku_cost_state_stock_item} without raising a violation, so the transaction is never marked
     * rollback-only; on Postgres a concurrent inserter of the same SKU makes this wait for the in-flight inserter and
     * the caller's re-read sees the winner's row. The seed carries no cost, so it is harmless if the caller rolls back.
     *
     * @return 1 if this call inserted the row, 0 if it already existed
     */
    @TenantAudited(
            reason = "names the tenant explicitly (the caller's resolved tenant), so the row is the bound tenant's on"
                    + " Postgres and on the H2 slices alike; the policy's WITH CHECK still refuses any other tenant")
    @Modifying
    @Query(value = """
                    INSERT INTO sku_cost_state
                        (tenant_id, cost_state_id, stock_item_id, avg_cost, on_hand_qty, standard_cost,
                         created_at, updated_at)
                    VALUES (:tenantId, :id, :stockItemId, NULL, 0, NULL, :now, :now)
                    ON CONFLICT DO NOTHING
                    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("stockItemId") String stockItemId,
            @Param("now") Instant now);
}
