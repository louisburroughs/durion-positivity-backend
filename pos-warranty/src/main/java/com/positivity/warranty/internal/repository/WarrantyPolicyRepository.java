package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.WarrantyPolicy;
import com.positivity.warranty.internal.enums.AppliesToType;
import com.positivity.warranty.internal.enums.CoverageType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WarrantyPolicyRepository extends JpaRepository<WarrantyPolicy, UUID> {

    @NonNull
    List<WarrantyPolicy> findByProviderId(@NonNull UUID providerId);

    @NonNull
    List<WarrantyPolicy> findByCoverageType(@NonNull CoverageType coverageType);

    /**
     * Policies in effect on the original sale date (PRD §3.2 / §6 step 1). The
     * {@code appliesTo} matching (product → manufacturer → category → ALL, most specific
     * wins) is evaluated in the service layer on this candidate set.
     */
    @NonNull
    @Query("select p from WarrantyPolicy p where p.effectiveFrom <= :saleDate"
            + " and (p.effectiveTo is null or p.effectiveTo >= :saleDate)")
    List<WarrantyPolicy> findEffectiveOn(@Param("saleDate") @NonNull LocalDate saleDate);

    /**
     * Auto-registration candidates (issue #925). Product membership (jsonb list containment
     * is not portable) and the effectivity window are filtered in the service layer.
     */
    @NonNull
    List<WarrantyPolicy> findByAutoRegisterTrueAndAppliesToType(@NonNull AppliesToType appliesToType);
}
