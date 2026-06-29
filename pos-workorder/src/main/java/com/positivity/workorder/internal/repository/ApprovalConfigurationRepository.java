package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ApprovalConfiguration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalConfigurationRepository extends JpaRepository<ApprovalConfiguration, UUID> {

    /**
     * Find the most specific approval configuration for a given location and
     * customer.
     * Priority: customer-specific > location-specific > default
     */
    @Query("SELECT ac FROM ApprovalConfiguration ac "
            + "WHERE (ac.customerId = :customerId OR ac.locationId = :locationId OR (ac.customerId IS NULL AND ac.locationId IS NULL)) "
            + "ORDER BY ac.priority DESC")
    List<ApprovalConfiguration> findApplicableConfigurations(
            @Param("locationId") UUID locationId, @Param("customerId") UUID customerId);

    Optional<ApprovalConfiguration> findByLocationIdAndCustomerId(UUID locationId, UUID customerId);

    Optional<ApprovalConfiguration> findByLocationIdAndCustomerIdIsNull(UUID locationId);

    Optional<ApprovalConfiguration> findByLocationIdIsNullAndCustomerIdIsNull();
}
