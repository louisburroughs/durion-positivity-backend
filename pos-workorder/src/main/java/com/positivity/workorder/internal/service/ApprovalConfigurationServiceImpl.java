package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.ApprovalConfigurationRequest;
import com.positivity.workorder.internal.dto.ApprovalConfigurationResponse;
import com.positivity.workorder.internal.entity.ApprovalConfiguration;
import com.positivity.workorder.internal.repository.ApprovalConfigurationRepository;
import com.positivity.workorder.service.ApprovalConfigurationService;

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
public class ApprovalConfigurationServiceImpl implements ApprovalConfigurationService {
    private final ApprovalConfigurationRepository approvalConfigurationRepository;

    @Override
    public List<ApprovalConfigurationResponse> getAllConfigurations() {
        return approvalConfigurationRepository.findAll()
                .stream()
                .map(this::entityToResponse)
                .toList();
    }

    @Override
    public Optional<ApprovalConfigurationResponse> getConfigurationById(UUID id) {
        return approvalConfigurationRepository.findById(id)
                .map(this::entityToResponse);
    }

    @Override
    @Transactional
    public ApprovalConfigurationResponse createConfiguration(ApprovalConfigurationRequest request) {
        ApprovalConfiguration configuration = new ApprovalConfiguration();
        configuration.setLocationId(request.getLocationId());
        configuration.setCustomerId(request.getCustomerId());
        configuration.setApprovalMethod(ApprovalConfiguration.ApprovalMethod.valueOf(request.getApprovalMethod()));
        configuration.setDeclineExpiryDays(request.getDeclineExpiryDays());
        configuration.setRequireSignature(request.getRequireSignature());
        configuration.setPriority(request.getPriority());

        ApprovalConfiguration saved = approvalConfigurationRepository.save(configuration);
        return entityToResponse(saved);
    }

    @Override
    @Transactional
    public ApprovalConfigurationResponse updateConfiguration(UUID id, ApprovalConfigurationRequest request) {
        ApprovalConfiguration existing = approvalConfigurationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Configuration not found: " + id));

        existing.setLocationId(request.getLocationId());
        existing.setCustomerId(request.getCustomerId());
        existing.setApprovalMethod(ApprovalConfiguration.ApprovalMethod.valueOf(request.getApprovalMethod()));
        existing.setDeclineExpiryDays(request.getDeclineExpiryDays());
        existing.setRequireSignature(request.getRequireSignature());
        existing.setPriority(request.getPriority());

        ApprovalConfiguration updated = approvalConfigurationRepository.save(existing);
        return entityToResponse(updated);
    }

    @Override
    public void deleteConfiguration(UUID id) {
        approvalConfigurationRepository.deleteById(id);
    }

    /**
     * Get the most specific configuration for a location and customer
     */
    @Override
    public Optional<ApprovalConfigurationResponse> getApplicableConfiguration(UUID locationId, UUID customerId) {
        // Try customer-specific first
        if (customerId != null) {
            Optional<ApprovalConfiguration> config = approvalConfigurationRepository
                    .findByLocationIdAndCustomerId(locationId, customerId);
            if (config.isPresent()) {
                return config.map(this::entityToResponse);
            }
        }

        // Try location-specific
        if (locationId != null) {
            Optional<ApprovalConfiguration> config = approvalConfigurationRepository
                    .findByLocationIdAndCustomerIdIsNull(locationId);
            if (config.isPresent()) {
                return config.map(this::entityToResponse);
            }
        }

        // Fall back to default
        return approvalConfigurationRepository.findByLocationIdIsNullAndCustomerIdIsNull()
                .map(this::entityToResponse);
    }

    /**
     * Convert entity to response DTO
     */
    private ApprovalConfigurationResponse entityToResponse(ApprovalConfiguration entity) {
        return ApprovalConfigurationResponse.builder()
                .id(entity.getId())
                .locationId(entity.getLocationId())
                .customerId(entity.getCustomerId())
                .approvalMethod(entity.getApprovalMethod().name())
                .declineExpiryDays(entity.getDeclineExpiryDays())
                .requireSignature(entity.getRequireSignature())
                .priority(entity.getPriority())
                .build();
    }
}
