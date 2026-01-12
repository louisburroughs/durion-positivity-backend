package com.positivity.workorder.service;

import com.positivity.events.EmitEvent;
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
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EstimateService {
    private final EstimateRepository estimateRepository;
    private final ApprovalConfigurationRepository approvalConfigurationRepository;
    private final EstimateSequenceRepository estimateSequenceRepository;
    private final CustomerRepository customerRepository;
    private final VehicleRepository vehicleRepository;

    public List<Estimate> getAllEstimates() {
        return estimateRepository.findAll();
    }

    public Optional<Estimate> getEstimateById(Long id) {
        return estimateRepository.findById(id);
    }

    public List<Estimate> getEstimatesByCustomer(Long customerId) {
        return estimateRepository.findByCustomerId(customerId);
    }

    public List<Estimate> getEstimatesByShop(Long shopId) {
        return estimateRepository.findByShopId(shopId);
    }

    /**
     * Create a new draft estimate for a customer and vehicle.
     * Validates that both customer and vehicle exist before creation.
     * Generates a unique estimate number and emits an EstimateCreated event.
     *
     * @param request the request containing customer, vehicle, and shop IDs
     * @param createdByUserId the ID of the user creating the estimate
     * @return the newly created estimate
     * @throws IllegalArgumentException if customer or vehicle is not found
     */
    @Transactional
    @EmitEvent(id = "EstimateCreated")
    public Estimate createDraftEstimate(CreateEstimateRequest request, Long createdByUserId) {
        log.info("Creating draft estimate for customer {} and vehicle {} by user {}",
                request.getCustomerId(), request.getVehicleId(), createdByUserId);

        // Validate customer exists
        if (!customerRepository.existsById(request.getCustomerId())) {
            log.warn("Customer with ID {} not found", request.getCustomerId());
            throw new IllegalArgumentException("Customer with ID " + request.getCustomerId() + " not found");
        }

        // Validate vehicle exists
        if (!vehicleRepository.existsById(request.getVehicleId())) {
            log.warn("Vehicle with ID {} not found", request.getVehicleId());
            throw new IllegalArgumentException("Vehicle with ID " + request.getVehicleId() + " not found");
        }

        // Generate unique estimate number
        String estimateNumber = generateEstimateNumber();

        // Get approval configuration for this customer/location
        ApprovalConfiguration config = getApprovalConfiguration(request.getShopId(), request.getCustomerId());

        // Create the estimate
        Estimate estimate = Estimate.builder()
                .estimateNumber(estimateNumber)
                .customerId(request.getCustomerId())
                .vehicleId(request.getVehicleId())
                .shopId(request.getShopId())
                .status(Estimate.EstimateStatus.DRAFT)
                .createdById(createdByUserId)
                .approvalConfigurationId(config.getId())
                .build();

        Estimate savedEstimate = estimateRepository.save(estimate);
        
        log.info("Estimate {} created for Customer {} by User {}", 
                savedEstimate.getEstimateNumber(), 
                savedEstimate.getCustomerId(), 
                savedEstimate.getCreatedById());

        return savedEstimate;
    }

    /**
     * Generate a unique estimate number in the format EST-XXXXX
     * Uses a sequence table to ensure uniqueness across transactions
     */
    private synchronized String generateEstimateNumber() {
        EstimateSequence sequence = estimateSequenceRepository.findAll()
                .stream()
                .findFirst()
                .orElseGet(() -> {
                    EstimateSequence newSeq = EstimateSequence.builder()
                            .lastSequenceNumber(10000L)
                            .build();
                    return estimateSequenceRepository.save(newSeq);
                });

        Long nextNumber = sequence.getLastSequenceNumber() + 1;
        sequence.setLastSequenceNumber(nextNumber);
        estimateSequenceRepository.save(sequence);

        return String.format("EST-%d", nextNumber);
    }

    @Transactional
    public Estimate createEstimate(Estimate estimate) {
        estimate.setStatus(Estimate.EstimateStatus.DRAFT);
        estimate.setCreatedAt(LocalDateTime.now());
        
        // Get approval configuration for this customer/location
        ApprovalConfiguration config = getApprovalConfiguration(estimate.getShopId(), estimate.getCustomerId());
        estimate.setApprovalConfigurationId(config.getId());
        
        return estimateRepository.save(estimate);
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
