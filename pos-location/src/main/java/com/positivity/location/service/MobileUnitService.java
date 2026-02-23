package com.positivity.location.service;

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
import org.springframework.data.domain.Page;

public interface MobileUnitService {

    MobileUnitResponse createMobileUnit(Map<String, Object> request);

    MobileUnitResponse createMobileUnit(MobileUnitRequest request);

    void validateCapabilityIds(List<?> capabilityIds);

    void validateDistanceTiers(List<?> tiers);

    Page<MobileUnitResponse> list(int page, int size);

    Optional<MobileUnitResponse> getById(UUID id);

    MobileUnitResponse patch(UUID id, Map<String, Object> patch);

    List<CoverageRuleResponse> replaceCoverageRules(UUID id, List<CoverageRuleRequest> rules);

    List<CoverageRuleResponse> replaceCoverageRules(String id, List<Map<String, Object>> rulePayload);

    List<CoverageRuleResponse> getCoverageRules(UUID id);

    List<EligibleMobileUnitResponse> findEligibleMobileUnits(String postalCode, String countryCode, Instant at);
}
