package com.positivity.shopmanager.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.positivity.shopmanager.internal.exception.SourceNotEligibleException;
import com.positivity.shopmanager.service.SourceEligibilityService;

@Slf4j
@Service
public class SourceEligibilityServiceImpl implements SourceEligibilityService {

    @Override
    public void validateEstimateEligibility(String estimateId, String facilityId) throws SourceNotEligibleException {
        log.debug("Validating estimate eligibility: estimateId={}, facilityId={}", estimateId, facilityId);
        // Stub implementation for OpenAPI spec generation
    }

    @Override
    public void validateWorkOrderEligibility(String workOrderId, String facilityId) throws SourceNotEligibleException {
        log.debug("Validating workorder eligibility: workorderId={}, facilityId={}", workOrderId, facilityId);
        // Stub implementation for OpenAPI spec generation
    }

    @Override
    public String getEstimateStatus(String estimateId, String facilityId) {
        log.debug("Getting estimate status: estimateId={}, facilityId={}", estimateId, facilityId);
        return "APPROVED"; // Stub implementation
    }

    @Override
    public String getWorkOrderStatus(String workOrderId, String facilityId) {
        log.debug("Getting workorder status: workorderId={}, facilityId={}", workOrderId, facilityId);
        return "OPEN"; // Stub implementation
    }

    @Override
    public String getExistingAppointmentId(String sourceType, String sourceId, String facilityId) {
        log.debug("Getting existing appointment: sourceType={}, sourceId={}, facilityId={}", sourceType, sourceId,
                facilityId);
        return null; // Stub implementation
    }
}
