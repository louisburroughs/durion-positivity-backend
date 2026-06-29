package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.enums.EstimateStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
     * @param q           free-text query matched against the estimate number
     * @param customerIds customer ids resolved from a name search (must be non-empty for JPQL IN)
     * @param idQuery     the query parsed as a UUID, or {@code null} if not a UUID
     * @param pageable    pagination configuration
     * @return page of matching estimates
     */
    @Query("SELECT e FROM Estimate e WHERE LOWER(e.estimateNumber) LIKE LOWER(CONCAT('%', :q, '%')) "
            + "OR e.customerId IN :customerIds OR (:idQuery IS NOT NULL AND e.id = :idQuery)")
    Page<Estimate> searchByQuery(
            @Param("q") String q,
            @Param("customerIds") Collection<UUID> customerIds,
            @Param("idQuery") UUID idQuery,
            Pageable pageable);
}
