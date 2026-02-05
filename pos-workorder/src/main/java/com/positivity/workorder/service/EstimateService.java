package com.positivity.workorder.service;

import com.positivity.workorder.internal.dto.CreateEstimateRequest;
import com.positivity.workorder.internal.entity.ApprovalConfiguration;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateStatus;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.event.EstimateRevisedEvent;
import com.positivity.workorder.internal.repository.*;
import com.positivity.workorder.internal.service.BillingRulesClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
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
    private final WorkorderRepository workOrderRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final BillingRulesClientService billingRulesClientService;

    // Configuration defaults
    private static final String DEFAULT_CURRENCY = "USD";
    private static final Long DEFAULT_TAX_REGION_ID = 1L; // TODO: Get from configuration
    private static final Long DEFAULT_LOCATION_ID = 1L; // TODO: Get from user session

    public List<Estimate> getAllEstimates() {
        return estimateRepository.findAll();
    }

    public Optional<Estimate> getEstimateById(UUID id) {
        return estimateRepository.findById(id);
    }

    public List<Estimate> getEstimatesByCustomer(Long customerId) {
        return estimateRepository.findByCustomerId(customerId);
    }

    // @Deprecated
    // public List<Estimate> getEstimatesByShop(Long shopId) {
    // return getEstimatesByLocation(shopId);
    // }

    public List<Estimate> getEstimatesByLocation(Long locationId) {
        return estimateRepository.findByLocationId(locationId);
    }

    /**
     * Create a new draft estimate with proper validation and defaulting
     * 
     * @param request         The create estimate request with customer and vehicle
     *                        IDs
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
                .status(EstimateStatus.DRAFT)
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
     * 
     * @deprecated Use createEstimate(CreateEstimateRequest, Long) instead
     */
    @Deprecated
    @Transactional
    public Estimate createEstimate(Estimate estimate) {
        estimate.setStatus(EstimateStatus.DRAFT);
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

        estimate.setStatus(EstimateStatus.APPROVED);
        estimate.setApprovedAt(LocalDateTime.now());
        estimate.setApprovedBy(approvedByUserId);

        log.info("Estimate {} approved by user {}", estimateId, approvedByUserId);
        return estimateRepository.save(estimate);
    }

    @Transactional
    public Estimate approveEstimate(Long estimateId, Long customerId, String signatureData,
            String signatureMimeType, String signerName, String notes) {
        return approveEstimate(estimateId, customerId, signatureData, signatureMimeType,
                signerName, notes, null);
    }

    /**
     * Approve estimate with full parameters including purchase order.
     * CAP:092 Story #98 - Enforces PO requirement for commercial accounts.
     *
     * @param estimateId          estimate to approve
     * @param customerId          customer approving the estimate
     * @param signatureData       base64-encoded signature image
     * @param signatureMimeType   MIME type of signature
     * @param signerName          name of person signing
     * @param notes               approval notes
     * @param purchaseOrderNumber PO number (required if account requires PO)
     * @return approved estimate
     */
    @Transactional
    public Estimate approveEstimate(Long estimateId, Long customerId, String signatureData,
            String signatureMimeType, String signerName, String notes,
            @Nullable String purchaseOrderNumber) {
        Estimate estimate = estimateRepository.findById(estimateId)
                .orElseThrow(() -> new IllegalArgumentException("Estimate not found: " + estimateId));

        // Validate customer matches estimate
        if (!estimate.getCustomerId().equals(customerId)) {
            throw new IllegalArgumentException("Customer ID mismatch: estimate belongs to customer "
                    + estimate.getCustomerId() + ", but approval attempted for customer " + customerId);
        }

        if (!estimate.canApprove()) {
            throw new IllegalStateException("Estimate cannot be approved in current state: " + estimate.getStatus());
        }

        // CAP:092 Story #98: Enforce PO requirement for commercial accounts
        String partyId = String.valueOf(customerId); // Convert customerId to partyId
        if (billingRulesClientService.isPurchaseOrderRequired(partyId)) {
            if (purchaseOrderNumber == null || purchaseOrderNumber.isBlank()) {
                log.warn("Purchase order required but not provided for estimate {} (partyId={})",
                        estimateId, partyId);
                throw new IllegalArgumentException(
                        "Purchase order number is required for this customer account");
            }
            log.info("PO enforcement: validating PO {} for estimate {} (partyId={})",
                    purchaseOrderNumber, estimateId, partyId);
        }

        estimate.setStatus(EstimateStatus.APPROVED);
        estimate.setApprovedAt(LocalDateTime.now());
        estimate.setApprovedBy(customerId); // Using customerId as approver
        estimate.setSignatureData(signatureData);
        estimate.setSignatureMimeType(signatureMimeType);
        estimate.setSignerName(signerName);
        estimate.setApprovalNotes(notes);
        estimate.setPurchaseOrderNumber(purchaseOrderNumber);

        log.info("Estimate {} approved by customer {} with signature capture", estimateId, customerId);
        return estimateRepository.save(estimate);
    }

    @Transactional
    public Estimate declineEstimate(Long estimateId, String reason) {
        Estimate estimate = estimateRepository.findById(estimateId)
                .orElseThrow(() -> new IllegalArgumentException("Estimate not found: " + estimateId));

        if (!estimate.canDecline()) {
            throw new IllegalStateException("Estimate cannot be declined in current state: " + estimate.getStatus());
        }

        estimate.setStatus(EstimateStatus.DECLINED);
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
            throw new IllegalStateException(
                    "Estimate cannot be reopened - either not declined or expiry period has passed");
        }

        estimate.setStatus(EstimateStatus.DRAFT);
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

    /**
     * Update estimate financial details and publish revision event if total
     * changes.
     * This triggers the approval invalidation workflow for associated Workorders.
     * 
     * @param estimateId the ID of the estimate to update
     * @param subtotal   new subtotal amount
     * @param taxAmount  new tax amount
     * @param total      new total amount
     * @param userId     ID of user making the change
     * @return the updated estimate
     */
    @Transactional
    public Estimate updateEstimateFinancials(Long estimateId, BigDecimal subtotal,
            BigDecimal taxAmount, BigDecimal total,
            Long userId) {
        Estimate estimate = estimateRepository.findById(estimateId)
                .orElseThrow(() -> new IllegalArgumentException("Estimate not found: " + estimateId));

        // Capture old total for comparison
        BigDecimal oldTotal = estimate.getTotal();

        // Update financial fields
        estimate.setSubtotal(subtotal);
        estimate.setTaxAmount(taxAmount);
        estimate.setTotal(total);

        // Check if total changed (financially significant)
        boolean totalChanged = (oldTotal == null && total != null) ||
                (oldTotal != null && !oldTotal.equals(total));

        if (totalChanged) {
            // Increment version on financial changes
            estimate.setVersion(estimate.getVersion() + 1);

            log.info("Estimate {} total changed from {} to {}",
                    estimateId, oldTotal, total);
        }

        Estimate saved = estimateRepository.save(estimate);

        // Publish revision event if total changed
        if (totalChanged) {
            publishEstimateRevisedEvents(saved, oldTotal, total, userId);
        }

        return saved;
    }

    /**
     * Publish EstimateRevisedEvent for all Workorders associated with this
     * estimate.
     * This enables event-driven invalidation of approvals.
     */
    private void publishEstimateRevisedEvents(Estimate estimate, BigDecimal oldTotal,
            BigDecimal newTotal, Long userId) {
        List<Workorder> workOrders = workOrderRepository.findByEstimateId(estimate.getId());

        log.info("Publishing EstimateRevisedEvent for {} Workorders linked to estimate {}",
                workOrders.size(), estimate.getId());

        for (Workorder workOrder : workOrders) {
            EstimateRevisedEvent event = EstimateRevisedEvent.builder()
                    .estimateId(estimate.getId())
                    .workorderId(workOrder.getId())
                    .oldTotal(oldTotal != null ? oldTotal : BigDecimal.ZERO)
                    .newTotal(newTotal != null ? newTotal : BigDecimal.ZERO)
                    .changedBy(userId)
                    .timestamp(Instant.now())
                    .build();

            eventPublisher.publishEvent(event);

            log.info("Published EstimateRevisedEvent: estimateId={}, workOrderId={}, " +
                    "oldTotal={}, newTotal={}",
                    estimate.getId(), workOrder.getId(), oldTotal, newTotal);
        }
    }
}
