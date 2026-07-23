package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.AllocationEntity;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.AllocationState;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AllocationRepository extends JpaRepository<AllocationEntity, UUID> {

    List<AllocationEntity> findByReservation(ReservationEntity reservation);

    List<AllocationEntity> findByReservationAndAllocationState(ReservationEntity reservation, AllocationState state);

    /**
     * Set-based per-allocation consistency check (odoo-parity K3, issue #1032):
     * joins every allocation against its ledger footprint (allocation events
     * carry the allocation id as {@code source_transaction_id}) and returns
     * ONLY violating rows, so the scheduled verifier never materializes either
     * table in application memory. Event types are passed as enum names
     * ({@code event_type} is stored as text).
     *
     * <p>Expected footprint per (allocationState, locationId, status) — see
     * {@code AllocationConsistencyVerifier} for the derivation:
     * <ul>
     *   <li>SOFT or unlocated: no allocation ledger events at all;</li>
     *   <li>HARD located, status RELEASED: created == allocatedQuantity and
     *       released == created (outstanding 0);</li>
     *   <li>HARD located, status ALLOCATED/PICKED: created == allocatedQuantity
     *       and 0 &le; released &lt; created (outstanding in (0, quantity]);</li>
     *   <li>orphan branch: allocation events whose source transaction id
     *       matches no allocation row (including a null id) with a non-zero
     *       outstanding.</li>
     * </ul>
     */
    @Query(value = """
                        WITH alloc_ledger AS (
                            SELECT source_transaction_id,
                                   COALESCE(SUM(CASE WHEN event_type = :created THEN change_in_quantity ELSE 0 END), 0) AS created_qty,
                                   COALESCE(SUM(CASE WHEN event_type = :released THEN change_in_quantity ELSE 0 END), 0) AS released_qty
                            FROM inventory_ledger_entry
                            WHERE event_type IN (:created, :released)
                            GROUP BY source_transaction_id
                        )
                        SELECT CAST(a.allocation_id AS VARCHAR) AS allocationId,
                               CAST(a.allocation_state AS VARCHAR) AS allocationState,
                               CAST(a.status AS VARCHAR) AS status,
                               a.allocated_quantity AS allocatedQuantity,
                               COALESCE(l.created_qty, 0) AS ledgerCreated,
                               COALESCE(l.released_qty, 0) AS ledgerReleased
                        FROM inventory_allocation a
                        LEFT JOIN alloc_ledger l ON l.source_transaction_id = CAST(a.allocation_id AS VARCHAR)
                        WHERE ((a.allocation_state = 'SOFT' OR a.location_id IS NULL)
                               AND (COALESCE(l.created_qty, 0) <> 0 OR COALESCE(l.released_qty, 0) <> 0))
                           OR (a.allocation_state = 'HARD' AND a.location_id IS NOT NULL AND a.status = 'RELEASED'
                               AND (COALESCE(l.created_qty, 0) <> a.allocated_quantity
                                    OR COALESCE(l.released_qty, 0) <> COALESCE(l.created_qty, 0)))
                           OR (a.allocation_state = 'HARD' AND a.location_id IS NOT NULL AND a.status <> 'RELEASED'
                               AND (COALESCE(l.created_qty, 0) <> a.allocated_quantity
                                    OR COALESCE(l.released_qty, 0) < 0
                                    OR COALESCE(l.released_qty, 0) >= COALESCE(l.created_qty, 0)))
                        UNION ALL
                        SELECT l.source_transaction_id AS allocationId,
                               CAST('ORPHAN' AS VARCHAR) AS allocationState,
                               CAST('ORPHAN' AS VARCHAR) AS status,
                               CAST(0 AS INTEGER) AS allocatedQuantity,
                               l.created_qty AS ledgerCreated,
                               l.released_qty AS ledgerReleased
                        FROM alloc_ledger l
                        WHERE (l.source_transaction_id IS NULL
                               OR NOT EXISTS (SELECT 1 FROM inventory_allocation a
                                              WHERE CAST(a.allocation_id AS VARCHAR) = l.source_transaction_id))
                          AND l.created_qty <> l.released_qty
                        """, nativeQuery = true)
    List<AllocationConsistencyRow> findConsistencyViolations(
            @Param("created") String created, @Param("released") String released);

    /** One violating allocation (or orphan ledger key) from {@link #findConsistencyViolations}. Log-only projection. */
    interface AllocationConsistencyRow {
        /** Rendered as text in SQL; null for orphan allocation events without a source transaction id. */
        String getAllocationId();

        /** {@code SOFT}/{@code HARD}, or {@code ORPHAN} for unmatched ledger keys. */
        String getAllocationState();

        /** {@code ALLOCATED}/{@code PICKED}/{@code RELEASED}, or {@code ORPHAN}. */
        String getStatus();

        int getAllocatedQuantity();

        long getLedgerCreated();

        long getLedgerReleased();
    }
}
