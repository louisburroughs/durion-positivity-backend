package com.positivity.location.internal.service;

import com.positivity.location.internal.dto.CoverageRuleRequest;
import com.positivity.location.internal.dto.CoverageRuleResponse;
import com.positivity.location.internal.dto.EligibleMobileUnitResponse;
import com.positivity.location.internal.dto.MobileUnitRequest;
import com.positivity.location.internal.dto.MobileUnitResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;

public interface MobileUnitService {

    MobileUnitResponse createMobileUnit(Map<String, Object> request);

    MobileUnitResponse createMobileUnit(MobileUnitRequest request);

    void validateServiceCapabilityCodes(List<?> serviceCapabilityCodes);

    void validateDistanceTiers(List<?> tiers);

    Page<MobileUnitResponse> list(int page, int size);

    /**
     * A page of mobile units, optionally narrowed to one base location and/or status, each
     * optionally carrying its coverage rules (#2253).
     */
    @NonNull
    Page<MobileUnitResponse> list(
            int page, int size, @Nullable UUID baseLocationId, @Nullable String status, boolean includeCoverageRules);

    Optional<MobileUnitResponse> getById(UUID id);

    MobileUnitResponse patch(UUID id, Map<String, Object> patch);

    /**
     * Hard-delete a mobile unit and publish the {@code location.mobile-unit.deleted} tombstone
     * (issue #1668).
     *
     * @return {@code true} when a unit was deleted, {@code false} when none matched
     */
    boolean deleteMobileUnit(UUID id);

    List<CoverageRuleResponse> replaceCoverageRules(UUID id, List<CoverageRuleRequest> rules);

    List<CoverageRuleResponse> replaceCoverageRules(String id, List<Map<String, Object>> rulePayload);

    List<CoverageRuleResponse> getCoverageRules(UUID id);

    List<EligibleMobileUnitResponse> findEligibleMobileUnits(String postalCode, String countryCode, Instant at);
}
