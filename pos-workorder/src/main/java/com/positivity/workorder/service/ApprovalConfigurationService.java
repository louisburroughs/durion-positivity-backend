package com.positivity.workorder.service;

import com.positivity.workorder.internal.entity.ApprovalConfiguration;
import com.positivity.workorder.internal.repository.ApprovalConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApprovalConfigurationService {
    private final ApprovalConfigurationRepository approvalConfigurationRepository;

    public List<ApprovalConfiguration> getAllConfigurations() {
        return approvalConfigurationRepository.findAll();
    }

    public Optional<ApprovalConfiguration> getConfigurationById(UUID id) {
        return approvalConfigurationRepository.findById(id);
    }

    @Transactional
    public ApprovalConfiguration createConfiguration(ApprovalConfiguration configuration) {
        return approvalConfigurationRepository.save(configuration);
    }

    @Transactional
    public ApprovalConfiguration updateConfiguration(UUID id, ApprovalConfiguration updatedConfig) {
        ApprovalConfiguration existing = approvalConfigurationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Configuration not found: " + id));

        existing.setLocationId(updatedConfig.getLocationId());
        existing.setCustomerId(updatedConfig.getCustomerId());
        existing.setApprovalMethod(updatedConfig.getApprovalMethod());
        existing.setDeclineExpiryDays(updatedConfig.getDeclineExpiryDays());
        existing.setRequireSignature(updatedConfig.getRequireSignature());

        return approvalConfigurationRepository.save(existing);
    }

    public void deleteConfiguration(UUID id) {
        approvalConfigurationRepository.deleteById(id);
    }

    /**
     * Get the most specific configuration for a location and customer
     */
    public Optional<ApprovalConfiguration> getApplicableConfiguration(Long locationId, Long customerId) {
        // Try customer-specific first
        if (customerId != null) {
            Optional<ApprovalConfiguration> config = approvalConfigurationRepository
                    .findByLocationIdAndCustomerId(locationId, customerId);
            if (config.isPresent()) {
                return config;
            }
        }

        // Try location-specific
        if (locationId != null) {
            Optional<ApprovalConfiguration> config = approvalConfigurationRepository
                    .findByLocationIdAndCustomerIdIsNull(locationId);
            if (config.isPresent()) {
                return config;
            }
        }

        // Fall back to default
        return approvalConfigurationRepository.findByLocationIdIsNullAndCustomerIdIsNull();
    }
}
