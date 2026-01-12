package com.positivity.workorder.service;

import com.positivity.workorder.dto.CreateEstimateRequest;
import com.positivity.workorder.entity.ApprovalConfiguration;
import com.positivity.workorder.entity.Estimate;
import com.positivity.workorder.entity.EstimateSequence;
import com.positivity.workorder.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EstimateService {
    private final EstimateRepository estimateRepository;
    private final ApprovalConfigurationRepository approvalConfigurationRepository;
    
    // Configuration defaults
    private static final String DEFAULT_CURRENCY = "USD";
    private static final Long DEFAULT_TAX_REGION_ID = 1L; // TODO: Get from configuration
    private static final Long DEFAULT_LOCATION_ID = 1L; // TODO: Get from user session

    public List<Estimate> getAllEstimates() {
        return estimateRepository.findAll();
    }

    public Optional<Estimate> getEstimateById(Long id) {
        return estimateRepository.findById(id);
    }

    public List<Estimate> getEstimatesByCustomer(Long customerId) {
        return estimateRepository.findByCustomerId(customerId);
    }

    @Deprecated
    public List<Estimate> getEstimatesByShop(Long shopId) {
        return getEstimatesByLocation(shopId);
    }
    
    public List<Estimate> getEstimatesByLocation(Long locationId) {
        return estimateRepository.findByLocationId(locationId);
    }

    /**
     * Create a new draft estimate with proper validation and defaulting
     * @param request The create estimate request with customer and vehicle IDs
     * @param createdByUserId The user ID creating the estimate
     * @return The created estimate
     * @throws IllegalArgumentException if validation fails
     */
    @Transactional
    public Estimate createEstimate(CreateEstimateRequest request, Long createdByUserId) {
        log.info("Creating new estimate for customer {} and vehicle {}", 
                request.getCustomerId(), request.getVehicleId());
        
        // Validate required fields
        if (request.getCustomerId() == null) {
            log.warn("Attempt to create estimate without customerId");
            throw new IllegalArgumentException("customerId is required");
        }
        if (request.getVehicleId() == null) {
            log.warn("Attempt to create estimate without vehicleId");
            throw new IllegalArgumentException("vehicleId is required");
        }
        
        // Apply defaults
        Long locationId = request.getLocationId() != null 
                ? request.getLocationId() 
                : DEFAULT_LOCATION_ID;
        String currencyUomId = request.getCurrencyUomId() != null 
                ? request.getCurrencyUomId() 
                : DEFAULT_CURRENCY;
        Long taxRegionId = request.getTaxRegionId() != null 
                ? request.getTaxRegionId() 
                : DEFAULT_TAX_REGION_ID;
        
        // Generate unique estimate number
        String estimateNumber = generateEstimateNumber(locationId);
        
        // Get approval configuration
        ApprovalConfiguration config = getApprovalConfiguration(locationId, request.getCustomerId());
        
        // Build estimate entity
        Estimate estimate = Estimate.builder()
                .estimateNumber(estimateNumber)
                .customerId(request.getCustomerId())
                .vehicleId(request.getVehicleId())
                .locationId(locationId)
                .currencyUomId(currencyUomId)
                .taxRegionId(taxRegionId)
                .status(Estimate.EstimateStatus.DRAFT)
                .createdByUserId(createdByUserId)
                .createdAt(LocalDateTime.now())
                .approvalConfigurationId(config.getId())
                .build();
        
        Estimate saved = estimateRepository.save(estimate);
        
        log.info("Estimate created successfully: estimateId={}, estimateNumber={}", 
                saved.getId(), saved.getEstimateNumber());
        
        // TODO: Emit EstimateCreated audit event
        
        return saved;
    }

    /**
     * Legacy method for backward compatibility
     * @deprecated Use createEstimate(CreateEstimateRequest, Long) instead
     */
    @Deprecated
    @Transactional
    public Estimate createEstimate(Estimate estimate) {
        estimate.setStatus(Estimate.EstimateStatus.DRAFT);
        estimate.setCreatedAt(LocalDateTime.now());
        
        // Get approval configuration for this customer/location
        Long locationId = estimate.getLocationId() != null 
                ? estimate.getLocationId() 
                : estimate.getShopId();
        ApprovalConfiguration config = getApprovalConfiguration(locationId, estimate.getCustomerId());
        estimate.setApprovalConfigurationId(config.getId());
        
        // Generate estimate number if not set
        if (estimate.getEstimateNumber() == null && locationId != null) {
            estimate.setEstimateNumber(generateEstimateNumber(locationId));
        }
        
        return estimateRepository.save(estimate);
    }
    
    /**
     * Generate a unique estimate number for a location
     * Format: EST-YYYY-NNNN where YYYY is the year and NNNN is a sequential number
     */
    private String generateEstimateNumber(Long locationId) {
        int year = Year.now().getValue();
        String prefix = String.format("EST-%d-", year);
        
        // Find the next available number
        int sequence = 1000; // Start at 1000
        String estimateNumber;
        do {
            estimateNumber = prefix + sequence;
            sequence++;
        } while (estimateRepository.existsByLocationIdAndEstimateNumber(locationId, estimateNumber));
        
        return estimateNumber;
    }

    @Transactional
    public Estimate approveEstimate(Long estimateId, Long approvedByUserId) {
        Estimate estimate = estimateRepository.findById(estimateId)
                .orElseThrow(() -> new IllegalArgumentException("Estimate not found: " + estimateId));

        if (!estimate.canApprove()) {
            throw new IllegalStateException("Estimate cannot be approved in current state: " + estimate.getStatus());
        }

        estimate.setStatus(Estimate.EstimateStatus.APPROVED);
        estimate.setApprovedAt(LocalDateTime.now());
        estimate.setApprovedBy(approvedByUserId);
        
        log.info("Estimate {} approved by user {}", estimateId, approvedByUserId);
        return estimateRepository.save(estimate);
    }

    @Transactional
    public Estimate declineEstimate(Long estimateId, String reason) {
        Estimate estimate = estimateRepository.findById(estimateId)
                .orElseThrow(() -> new IllegalArgumentException("Estimate not found: " + estimateId));

        if (!estimate.canDecline()) {
            throw new IllegalStateException("Estimate cannot be declined in current state: " + estimate.getStatus());
        }

        estimate.setStatus(Estimate.EstimateStatus.DECLINED);
        estimate.setDeclinedAt(LocalDateTime.now());
        estimate.setDeclineReason(reason);
        
        // Set expiry date based on configuration
        ApprovalConfiguration config = getApprovalConfigurationById(estimate.getApprovalConfigurationId());
        estimate.setExpiresAt(LocalDateTime.now().plusDays(config.getDeclineExpiryDays()));
        
        log.info("Estimate {} declined with reason: {}", estimateId, reason);
        return estimateRepository.save(estimate);
    }

    @Transactional
    public Estimate reopenEstimate(Long estimateId) {
        Estimate estimate = estimateRepository.findById(estimateId)
                .orElseThrow(() -> new IllegalArgumentException("Estimate not found: " + estimateId));

        ApprovalConfiguration config = getApprovalConfigurationById(estimate.getApprovalConfigurationId());
        
        if (!estimate.canReopen(config.getDeclineExpiryDays())) {
            throw new IllegalStateException("Estimate cannot be reopened - either not declined or expiry period has passed");
        }

        estimate.setStatus(Estimate.EstimateStatus.DRAFT);
        estimate.setDeclinedAt(null);
        estimate.setDeclineReason(null);
        estimate.setExpiresAt(null);
        
        log.info("Estimate {} reopened from declined state", estimateId);
        return estimateRepository.save(estimate);
    }

    /**
     * Get the most specific approval configuration for a location and customer.
     * Returns default configuration if none found.
     */
    public ApprovalConfiguration getApprovalConfiguration(Long locationId, Long customerId) {
        List<ApprovalConfiguration> configs = approvalConfigurationRepository
                .findApplicableConfigurations(locationId, customerId);
        
        if (configs.isEmpty()) {
            // Create and return default configuration
            return ApprovalConfiguration.builder()
                    .approvalMethod(ApprovalConfiguration.ApprovalMethod.CLICK_CONFIRM)
                    .declineExpiryDays(30)
                    .requireSignature(false)
                    .priority(0)
                    .build();
        }
        
        // Return highest priority configuration
        return configs.get(0);
    }

    private ApprovalConfiguration getApprovalConfigurationById(Long configId) {
        if (configId == null) {
            return ApprovalConfiguration.builder()
                    .approvalMethod(ApprovalConfiguration.ApprovalMethod.CLICK_CONFIRM)
                    .declineExpiryDays(30)
                    .requireSignature(false)
                    .priority(0)
                    .build();
        }
        return approvalConfigurationRepository.findById(configId)
                .orElseThrow(() -> new IllegalArgumentException("Approval configuration not found: " + configId));
    }

    public void deleteEstimate(Long id) {
        estimateRepository.deleteById(id);
    }
}
