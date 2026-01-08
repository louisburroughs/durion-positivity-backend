package com.positivity.workorder.repository;

import com.positivity.workorder.entity.ApprovalConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApprovalConfigurationRepository extends JpaRepository<ApprovalConfiguration, Long> {
    
    /**
     * Find the most specific approval configuration for a given location and customer.
     * Priority: customer-specific > location-specific > default
     */
    @Query("SELECT ac FROM ApprovalConfiguration ac " +
           "WHERE (ac.customerId = :customerId OR ac.locationId = :locationId OR (ac.customerId IS NULL AND ac.locationId IS NULL)) " +
           "ORDER BY ac.priority DESC")
    List<ApprovalConfiguration> findApplicableConfigurations(@Param("locationId") Long locationId, @Param("customerId") Long customerId);

    Optional<ApprovalConfiguration> findByLocationIdAndCustomerId(Long locationId, Long customerId);
    Optional<ApprovalConfiguration> findByLocationIdAndCustomerIdIsNull(Long locationId);
    Optional<ApprovalConfiguration> findByLocationIdIsNullAndCustomerIdIsNull();
}
