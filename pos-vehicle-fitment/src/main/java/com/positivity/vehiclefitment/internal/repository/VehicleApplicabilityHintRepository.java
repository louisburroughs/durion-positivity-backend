package com.positivity.vehiclefitment.internal.repository;

import com.positivity.vehiclefitment.internal.entity.VehicleApplicabilityHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for VehicleApplicabilityHint entities.
 */
@Repository
public interface VehicleApplicabilityHintRepository extends JpaRepository<VehicleApplicabilityHint, Long> {
    
    /**
     * Find all hints for a specific product.
     */
    List<VehicleApplicabilityHint> findByProductId(Long productId);
    
    /**
     * Find all hints that contain tags matching the given tag type and value.
     */
    @Query("SELECT DISTINCT h FROM VehicleApplicabilityHint h JOIN h.fitmentTags t " +
           "WHERE t.tagType = :tagType AND LOWER(t.tagValue) = LOWER(:tagValue)")
    List<VehicleApplicabilityHint> findByTagTypeAndValue(@Param("tagType") String tagType, 
                                                          @Param("tagValue") String tagValue);
}
