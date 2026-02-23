package com.positivity.workorder.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.positivity.workorder.internal.dto.ApprovalConfigurationRequest;
import com.positivity.workorder.internal.dto.ApprovalConfigurationResponse;

public interface ApprovalConfigurationService {

    List<ApprovalConfigurationResponse> getAllConfigurations();

    Optional<ApprovalConfigurationResponse> getConfigurationById(UUID id);

    ApprovalConfigurationResponse createConfiguration(ApprovalConfigurationRequest request);

    ApprovalConfigurationResponse updateConfiguration(UUID id, ApprovalConfigurationRequest request);

    void deleteConfiguration(UUID id);

    /**
     * Get the most specific configuration for a location and customer
     */
    Optional<ApprovalConfigurationResponse> getApplicableConfiguration(UUID locationId, UUID customerId);

}