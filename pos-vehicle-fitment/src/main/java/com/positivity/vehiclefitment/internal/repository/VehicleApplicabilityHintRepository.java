package com.positivity.vehiclefitment.internal.repository;

import com.positivity.vehiclefitment.internal.entity.VehicleApplicabilityHint;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for VehicleApplicabilityHint entities.
 */
public interface VehicleApplicabilityHintRepository extends JpaRepository<VehicleApplicabilityHint, UUID> {

    /**
     * Find all hints for a specific product.
     */
    List<VehicleApplicabilityHint> findByProductId(UUID productId);

    /**
     * Find all hints that contain tags matching the given tag type and value.
     */
    @Query("SELECT DISTINCT h FROM VehicleApplicabilityHint h JOIN h.fitmentTags t "
            + "WHERE t.tagType = :tagType AND LOWER(t.tagValue) = LOWER(:tagValue)")
    List<VehicleApplicabilityHint> findByTagTypeAndValue(
            @Param("tagType") String tagType, @Param("tagValue") String tagValue);
}
