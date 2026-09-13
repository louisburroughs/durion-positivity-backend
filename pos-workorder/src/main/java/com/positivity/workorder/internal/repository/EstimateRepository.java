package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.enums.EstimateStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EstimateRepository extends JpaRepository<Estimate, UUID> {
    List<Estimate> findByCustomerId(UUID customerId);

    Optional<Estimate> findByAppointmentId(UUID appointmentId);

    // Issue #15 (CAP-248): Paginated retrieval methods required for estimate search
    // endpoint.

    /**
     * Returns a page of estimates for a given customer.
     *
     * @param customerId filter by customer UUID
     * @param pageable   pagination configuration
     * @return page of matching estimates
     */
    Page<Estimate> findByCustomerId(UUID customerId, Pageable pageable);

    /**
     * Returns a page of estimates for a given vehicle.
     *
     * @param vehicleId filter by vehicle UUID
     * @param pageable  pagination configuration
     * @return page of matching estimates
     */
    Page<Estimate> findByVehicleId(UUID vehicleId, Pageable pageable);

    /**
     * Returns a page of estimates matching both customer and vehicle.
     *
     * @param customerId filter by customer UUID
     * @param vehicleId  filter by vehicle UUID
     * @param pageable   pagination configuration
     * @return page of matching estimates
     */
    Page<Estimate> findByCustomerIdAndVehicleId(UUID customerId, UUID vehicleId, Pageable pageable);

    List<Estimate> findByLocationId(UUID locationId);

    /**
     * The estimate book narrowed to a caller's location reach (ADR-0061 §3, #1872). Never called
     * with an empty collection — the service answers an empty list for an empty reach rather than
     * handing {@code IN ()} to the database.
     */
    List<Estimate> findByLocationIdIn(Collection<UUID> locationIds);

    List<Estimate> findByStatus(EstimateStatus status);

    boolean existsByLocationIdAndEstimateNumber(UUID locationId, String estimateNumber);

    /**
     * Find estimates by status where expiration timestamp is before the given date.
     * Used by approval expiration job to find expired pending approvals.
     * CAP:003 Issue #204 - Handle Approval Expiration
     *
     * @param status          estimate status to filter by
     * @param expiresAtBefore find estimates expired before this timestamp
     * @return list of expired estimates in the given status
     */
    List<Estimate> findByStatusAndExpiresAtBefore(EstimateStatus status, LocalDateTime expiresAtBefore);

    /**
     * Searches estimates by free-text query matching the estimate number (case-insensitive),
     * a set of customer ids resolved from a customer-name search, or the estimate id directly.
     *
     * <p>{@code q} must not be null, and the {@code @NonNull} is load-bearing rather than
     * decorative. It is the one parameter here that is never compared with a column — it only ever
     * reaches PostgreSQL inside {@code LOWER(CONCAT(…))} — so there is nothing for the server to
     * infer its type from except the value itself. A bound varchar types the concatenation and the
     * statement parses; a null would leave Hibernate to bind it opaquely, PostgreSQL to resolve
     * {@code unknown || unknown} as {@code bytea}, and the whole statement to be rejected at parse
     * time with {@code function lower(bytea) does not exist} — the second failure mode of issue
     * #1891, and the one that {@code pos-invoice}'s line search was actually suffering. The caller
     * guarantees it: {@code EstimateSearchController} only takes this path for a non-blank query.
     *
     * <p>{@code idQuery} is safe null or not, because it is compared with {@code e.id} — a UUID
     * column the server can infer the placeholder's type from in either direction.
     *
     * @param q           free-text query matched against the estimate number; never null
     * @param customerIds customer ids resolved from a name search (must be non-empty for JPQL IN)
     * @param idQuery     the query parsed as a UUID, or {@code null} if not a UUID
     * @param pageable    pagination configuration
     * @return page of matching estimates
     */
    @Query("SELECT e FROM Estimate e WHERE LOWER(e.estimateNumber) LIKE LOWER(CONCAT('%', :q, '%')) "
            + "OR e.customerId IN :customerIds OR (:idQuery IS NOT NULL AND e.id = :idQuery)")
    Page<Estimate> searchByQuery(
            @Param("q") @NonNull String q,
            @Param("customerIds") Collection<UUID> customerIds,
            @Param("idQuery") @Nullable UUID idQuery,
            Pageable pageable);
}
