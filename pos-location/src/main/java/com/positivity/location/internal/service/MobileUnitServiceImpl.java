package com.positivity.location.internal.service;

import com.positivity.location.internal.repository.MobileUnitCoverageRuleRepository;
import com.positivity.location.internal.repository.MobileUnitRepository;
import com.positivity.location.internal.repository.ServiceAreaRepository;
import com.positivity.location.internal.repository.TravelBufferPolicyRepository;
import com.positivity.location.service.MobileUnitService;
import org.springframework.stereotype.Service;

/**
 * Spring service implementation for mobile unit APIs.
 *
 * Issue: #76
 */
@Service
public class MobileUnitServiceImpl extends MobileUnitService {

    public MobileUnitServiceImpl(
            MobileUnitRepository mobileUnitRepository,
            MobileUnitCoverageRuleRepository coverageRuleRepository,
            ServiceAreaRepository serviceAreaRepository,
            TravelBufferPolicyRepository travelBufferPolicyRepository) {
        super(mobileUnitRepository, coverageRuleRepository, serviceAreaRepository, travelBufferPolicyRepository);
    }
}
