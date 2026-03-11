package com.positivity.workorder.internal.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationRequest.TaxAddress;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxLineItem;
import com.positivity.tax.common.enums.TaxReferenceType;
import com.positivity.workorder.internal.client.DocumentClient;
import com.positivity.workorder.internal.client.PeopleLocationClient;
import com.positivity.workorder.internal.client.TaxClient;
import com.positivity.workorder.internal.dto.AddEstimateItemRequest;
import com.positivity.workorder.internal.dto.CreateEstimateFromAppointmentRequest;
import com.positivity.workorder.internal.dto.CreateEstimateFromAppointmentResponse;
import com.positivity.workorder.internal.dto.CreateEstimateRequest;
import com.positivity.workorder.internal.dto.EstimateItemResponse;
import com.positivity.workorder.internal.dto.EstimateResponse;
import com.positivity.workorder.internal.dto.EstimateSnapshotResponse;
import com.positivity.workorder.internal.dto.EstimateSummaryResponse;
import com.positivity.workorder.internal.dto.UpdateEstimateItemRequest;
import com.positivity.workorder.internal.entity.ApprovalConfiguration;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.entity.EstimateSnapshot;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.event.EstimateCreatedEvent;
import com.positivity.workorder.internal.event.EstimateRevisedEvent;
import com.positivity.workorder.internal.repository.ApprovalConfigurationRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.EstimateSnapshotRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.service.BillingRulesClientService;
import com.positivity.workorder.service.EstimateService;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public class EstimateServiceImpl implements EstimateService {
        private final Clock clock;

        private static final String ESTIMATE_NOT_FOUND = "Estimate not found: ";
        private static final BigDecimal FALLBACK_TAX_RATE = new BigDecimal("0.0825");
        private final EstimateRepository estimateRepository;
        private final EstimateItemRepository estimateItemRepository;
        private final EstimateSnapshotRepository estimateSnapshotRepository;
        private final ApprovalConfigurationRepository approvalConfigurationRepository;
        private final WorkorderRepository workOrderRepository;
        private final ApplicationEventPublisher eventPublisher;
        private final BillingRulesClientService billingRulesClientService;
        private final TaxClient taxClient;
        private final PeopleLocationClient peopleLocationClient;
        private final DocumentClient documentClient;
        private final ObjectMapper objectMapper;

        // Configuration defaults
        private static final String DEFAULT_CURRENCY = "USD";
        private static final String DEFAULT_TAX_REGION_ID_PROPERTY = "workorder.estimate.default-tax-region-id";
        private static final String FALLBACK_LOCATION_ID_PROPERTY = "workorder.estimate.fallback-location-id";
        private static final UUID DEFAULT_TAX_REGION_ID = UUID.fromString(
                        System.getProperty(DEFAULT_TAX_REGION_ID_PROPERTY, "00000000-0000-0000-0000-000000000001"));
        private static final UUID FALLBACK_LOCATION_ID = UUID.fromString(
                        System.getProperty(FALLBACK_LOCATION_ID_PROPERTY, "00000000-0000-0000-0000-000000000001"));

        // Tax category constants
        private static final String TAX_CATEGORY_GOODS = "GOODS";
        private static final String TAX_CATEGORY_SERVICES = "SERVICES";

        // Default postal code - should be replaced with location service lookup
        private static final String DEFAULT_POSTAL_CODE = "78701"; // Austin, TX

        @Override
        public List<EstimateResponse> getAllEstimates() {
                return estimateRepository.findAll()
                                .stream()
                                .map(EstimateResponse::fromEntity)
                                .toList();
        }

        @Override
        public Optional<EstimateResponse> getEstimateById(UUID id) {
                return estimateRepository.findById(id)
                                .map(EstimateResponse::fromEntity);
        }

        @Override
        public List<EstimateResponse> getEstimatesByCustomer(UUID customerId) {
                return estimateRepository.findByCustomerId(customerId)
                                .stream()
                                .map(EstimateResponse::fromEntity)
                                .toList();
        }

        @Override
        public List<EstimateResponse> getEstimatesByLocation(UUID locationId) {
                return estimateRepository.findByLocationId(locationId)
                                .stream()
                                .map(EstimateResponse::fromEntity)
                                .toList();
        }

        @Override
        @NonNull
        public Page<EstimateSummaryResponse> searchEstimates(
                        @Nullable UUID customerId,
                        @Nullable UUID vehicleId,
                        @NonNull Pageable pageable) {
                Page<Estimate> estimates;

                if (customerId != null && vehicleId != null) {
                        estimates = estimateRepository.findByCustomerIdAndVehicleId(customerId, vehicleId, pageable);
                } else if (customerId != null) {
                        estimates = estimateRepository.findByCustomerId(customerId, pageable);
                } else if (vehicleId != null) {
                        estimates = estimateRepository.findByVehicleId(vehicleId, pageable);
                } else {
                        estimates = estimateRepository.findAll(pageable);
                }

                return estimates.map(this::toEstimateSummaryResponse);
        }

        /**
         * Line items intentionally omitted in paginated search summaries for
         * performance use getEstimateById to retrieve full line item detail.
         * 
         * @param estimate
         * @return
         */
        @NonNull
        private EstimateSummaryResponse toEstimateSummaryResponse(@NonNull Estimate estimate) {
                return EstimateSummaryResponse.builder()
                                .id(estimate.getId())
                                .estimateNumber(estimate.getEstimateNumber())
                                .status(estimate.getStatus())
                                .customerId(estimate.getCustomerId())
                                .vehicleId(estimate.getVehicleId())
                                .locationId(estimate.getLocationId())
                                .createdAt(estimate.getCreatedAt())
                                .expiresAt(estimate.getExpiresAt())
                                .subtotal(estimate.getSubtotal())
                                .taxAmount(estimate.getTaxAmount())
                                .total(estimate.getTotal())
                                .currencyUomId(estimate.getCurrencyUomId())
                                .partItems(List.of())
                                .laborItems(List.of())
                                .build();
        }

        /**
         * Create a new draft estimate with proper validation and defaulting
         * 
         * @param request         The create estimate request with customer and vehicle
         *                        IDs
         * @param createdByUserId The user ID creating the estimate
         * @return The created estimate DTO
         * @throws IllegalArgumentException if validation fails
         */
        @Override
        @Transactional
        public EstimateResponse createEstimate(CreateEstimateRequest request, String username) {
                log.info("Creating new estimate for customer {} and vehicle {}",
                                request.getCustomerId(), request.getVehicleId());

                validateCreateEstimateRequest(request);

                // Apply defaults
                UUID locationId = request.getLocationId() != null
                                ? request.getLocationId()
                                : peopleLocationClient.resolveCurrentUserPrimaryLocation()
                                                .orElse(FALLBACK_LOCATION_ID);
                String currencyUomId = request.getCurrencyUomId() != null
                                ? request.getCurrencyUomId()
                                : DEFAULT_CURRENCY;
                UUID taxRegionId = request.getTaxRegionId() != null
                                ? request.getTaxRegionId()
                                : DEFAULT_TAX_REGION_ID;

                // Generate unique estimate number
                String estimateNumber = generateEstimateNumber(locationId);

                // Get approval configuration
                ApprovalConfiguration config = getApprovalConfiguration(locationId, request.getCustomerId());

                // Build estimate entity
                String resolvedUsername = SecurityContextHelper.getCurrentUsernameOrDefault(username);
                String userId = SecurityContextHelper.getCurrentUsernameOrDefault(resolvedUsername);
                Estimate estimate = Estimate.builder()
                                .estimateNumber(estimateNumber)
                                .customerId(request.getCustomerId())
                                .vehicleId(request.getVehicleId())
                                .locationId(locationId)
                                .currencyUomId(currencyUomId)
                                .taxRegionId(taxRegionId)
                                .status(EstimateStatus.DRAFT)
                                .createdByUserId(resolvedUsername)
                                .createdById(userId)
                                .subtotal(request.getSubtotal())
                                .taxAmount(request.getTaxAmount())
                                .total(request.getTotal())
                                .approvalConfiguration(config)
                                .crmPartyId(request.getCrmPartyId())
                                .crmVehicleId(request.getCrmVehicleId())
                                .crmContactIds(request.getCrmContactIds() != null
                                                ? new ArrayList<>(request.getCrmContactIds())
                                                : new ArrayList<>())
                                .build();

                Estimate saved = estimateRepository.save(estimate);

                log.info("Estimate created successfully: estimateId={}, estimateNumber={}",
                                saved.getId(), saved.getEstimateNumber());

                eventPublisher.publishEvent(EstimateCreatedEvent.builder()
                                .estimateId(saved.getId())
                                .estimateNumber(saved.getEstimateNumber())
                                .customerId(saved.getCustomerId())
                                .vehicleId(saved.getVehicleId())
                                .locationId(saved.getLocationId())
                                .createdBy(username)
                                .timestamp(Instant.now(clock).truncatedTo(ChronoUnit.MILLIS))
                                .build());

                return EstimateResponse.fromEntity(saved);
        }

        private void validateCreateEstimateRequest(CreateEstimateRequest request) {
                if (request.getCustomerId() == null) {
                        log.warn("Attempt to create estimate without customerId");
                        throw new IllegalArgumentException("customerId is required");
                }
                if (request.getVehicleId() == null) {
                        log.warn("Attempt to create estimate without vehicleId");
                        throw new IllegalArgumentException("vehicleId is required");
                }

                if (request.getSubtotal() != null && request.getSubtotal().compareTo(BigDecimal.ZERO) < 0) {
                        throw new IllegalArgumentException("subtotal must be non-negative");
                }
                if (request.getTaxAmount() != null && request.getTaxAmount().compareTo(BigDecimal.ZERO) < 0) {
                        throw new IllegalArgumentException("taxAmount must be non-negative");
                }
                if (request.getTotal() != null && request.getTotal().compareTo(BigDecimal.ZERO) < 0) {
                        throw new IllegalArgumentException("total must be non-negative");
                }
                if (request.getSubtotal() != null && request.getTaxAmount() != null && request.getTotal() != null) {
                        BigDecimal calculatedTotal = request.getSubtotal().add(request.getTaxAmount());
                        if (calculatedTotal.compareTo(request.getTotal()) != 0) {
                                throw new IllegalStateException("subtotal + taxAmount must equal total");
                        }
                }
        }

        /**
         * Generate a unique estimate number for a location
         * Format: EST-YYYY-NNNN where YYYY is the year and NNNN is a sequential number
         */
        private String generateEstimateNumber(UUID locationId) {
                int year = Year.now(clock).getValue();
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

        @Override
        @Transactional
        public EstimateResponse approveEstimate(UUID estimateId, UUID approvedByCustomerId) {
                Estimate estimate = estimateRepository.findById(estimateId)
                                .orElseThrow(() -> new EntityNotFoundException(ESTIMATE_NOT_FOUND + estimateId));

                if (!estimate.canApprove()) {
                        throw new IllegalStateException(
                                        "Estimate cannot be approved in current state: " + estimate.getStatus());
                }

                estimate.setStatus(EstimateStatus.APPROVED);
                estimate.setApprovedAt(LocalDateTime.now(clock));
                estimate.setApprovedBy(approvedByCustomerId);

                log.info("Estimate {} approved by customer {}", estimateId, approvedByCustomerId);
                return EstimateResponse.fromEntity(estimateRepository.save(estimate));
        }

        @Override
        @Transactional
        public EstimateResponse approveEstimate(UUID estimateId, UUID customerId, String signatureData,
                        String signatureMimeType, String signerName, String notes) {
                return EstimateResponse
                                .fromEntity(approveEstimateWithSignatureInternal(estimateId, customerId, signatureData,
                                                signatureMimeType, signerName, notes, null, null));
        }

        /**
         * Approve estimate with full parameters including purchase order.
         * CAP:092 Story #98 - Enforces PO requirement for commercial accounts.
         * CAP:003 - Supports selective line item approval.
         *
         * @param estimateId          estimate to approve
         * @param customerId          customer approving the estimate
         * @param signatureData       base64-encoded signature image
         * @param signatureMimeType   MIME type of signature
         * @param signerName          name of person signing
         * @param notes               approval notes
         * @param purchaseOrderNumber PO number (required if account requires PO)
         * @param lineItemApprovals   optional selective line item approvals (null =
         *                            approve all)
         * @return approved estimate
         */
        @Override
        @Transactional
        @SuppressWarnings("java:S107") // Suppress "too many parameters" warning for this approval method
        public EstimateResponse approveEstimate(UUID estimateId, UUID customerId, String signatureData,
                        String signatureMimeType, String signerName, String notes,
                        @Nullable String purchaseOrderNumber,
                        @Nullable List<com.positivity.workorder.internal.dto.LineItemApprovalDto> lineItemApprovals) {
                return EstimateResponse
                                .fromEntity(approveEstimateWithSignatureInternal(estimateId, customerId, signatureData,
                                                signatureMimeType, signerName, notes, purchaseOrderNumber,
                                                lineItemApprovals));
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
        @Override
        @Transactional
        public EstimateResponse approveEstimate(UUID estimateId, UUID customerId, String signatureData,
                        String signatureMimeType, String signerName, String notes,
                        @Nullable String purchaseOrderNumber) {
                return EstimateResponse
                                .fromEntity(approveEstimateWithSignatureInternal(estimateId, customerId, signatureData,
                                                signatureMimeType, signerName, notes, purchaseOrderNumber, null));
        }

        /**
         * Internal helper for approving estimates with signature. Used by public API
         * and internal calls.
         * Runs within the transaction context of the caller.
         * CAP:003 - Handles selective line item approval.
         */
        @SuppressWarnings("java:S107") // Suppress "too many parameters" warning for this approval method
        private Estimate approveEstimateWithSignatureInternal(UUID estimateId, UUID customerId, String signatureData,
                        String signatureMimeType, String signerName, String notes,
                        @Nullable String purchaseOrderNumber,
                        @Nullable List<com.positivity.workorder.internal.dto.LineItemApprovalDto> lineItemApprovals) {
                Estimate estimate = estimateRepository.findById(estimateId)
                                .orElseThrow(() -> new EntityNotFoundException(ESTIMATE_NOT_FOUND + estimateId));

                // Validate customer matches estimate
                if (!estimate.getCustomerId().equals(customerId)) {
                        throw new IllegalArgumentException("Customer ID mismatch: estimate belongs to customer "
                                        + estimate.getCustomerId() + ", but approval attempted for customer "
                                        + customerId);
                }

                if (!estimate.canApprove()) {
                        throw new IllegalStateException(
                                        "Estimate cannot be approved in current state: " + estimate.getStatus());
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

                // CAP:003 - Process selective line item approvals
                processLineItemApprovals(estimateId, lineItemApprovals);

                estimate.setStatus(EstimateStatus.APPROVED);
                estimate.setApprovedAt(LocalDateTime.now(clock));
                // Customer who approved the estimate (via signature capture)
                estimate.setApprovedBy(customerId);
                estimate.setSignatureData(signatureData);
                estimate.setSignatureMimeType(signatureMimeType);
                estimate.setSignerName(signerName);
                estimate.setApprovalNotes(notes);
                estimate.setPurchaseOrderNumber(purchaseOrderNumber);

                log.info("Estimate {} approved by customer {} with signature capture", estimateId, customerId);
                return estimateRepository.save(estimate);
        }

        /**
         * Process selective line item approvals for CAP:003.
         * If lineItemApprovals is null or empty, all items are implicitly approved.
         * 
         * @param estimateId        estimate ID
         * @param lineItemApprovals list of line item approval decisions (null = approve
         *                          all)
         */
        private void processLineItemApprovals(UUID estimateId,
                        @Nullable List<com.positivity.workorder.internal.dto.LineItemApprovalDto> lineItemApprovals) {
                List<EstimateItem> allItems = estimateItemRepository.findByEstimate_IdAndDeletedFalse(estimateId);

                if (lineItemApprovals == null || lineItemApprovals.isEmpty()) {
                        // No explicit line item approvals provided - approve all items
                        log.info("No selective line item approvals provided for estimate {} - approving all {} items",
                                        estimateId, allItems.size());
                        for (EstimateItem item : allItems) {
                                item.setApprovalStatus(
                                                com.positivity.workorder.internal.enums.ApprovalStatus.APPROVED);
                                item.setApprovalTimestamp(LocalDateTime.now(clock));
                                estimateItemRepository.save(item);
                        }
                        return;
                }

                // Process selective approvals
                Map<UUID, com.positivity.workorder.internal.dto.LineItemApprovalDto> approvalMap = new HashMap<>();
                for (com.positivity.workorder.internal.dto.LineItemApprovalDto approval : lineItemApprovals) {
                        approvalMap.put(approval.getLineItemId(), approval);
                }

                for (EstimateItem item : allItems) {
                        com.positivity.workorder.internal.dto.LineItemApprovalDto approval = approvalMap
                                        .get(item.getId());

                        if (approval != null) {
                                // Explicit approval/rejection provided
                                if (Boolean.TRUE.equals(approval.getApproved())) {
                                        item.setApprovalStatus(
                                                        com.positivity.workorder.internal.enums.ApprovalStatus.APPROVED);
                                        log.debug("Line item {} explicitly approved", item.getId());
                                } else {
                                        item.setApprovalStatus(
                                                        com.positivity.workorder.internal.enums.ApprovalStatus.DECLINED);
                                        item.setRejectionReason(approval.getRejectionReason());
                                        log.debug("Line item {} declined - reason: {}", item.getId(),
                                                        approval.getRejectionReason());
                                }
                                item.setApprovalNotes(approval.getNotes());
                                item.setApprovalTimestamp(LocalDateTime.now(clock));
                        } else {
                                // No explicit decision - default to approved
                                item.setApprovalStatus(
                                                com.positivity.workorder.internal.enums.ApprovalStatus.APPROVED);
                                item.setApprovalTimestamp(LocalDateTime.now(clock));
                                log.debug("Line item {} implicitly approved (no explicit decision)", item.getId());
                        }

                        estimateItemRepository.save(item);
                }

                log.info("Processed selective approvals for estimate {}: {} items total, {} explicit decisions provided",
                                estimateId, allItems.size(), lineItemApprovals.size());
        }

        @Override
        @Transactional
        public EstimateResponse declineEstimate(UUID estimateId, String reason) {
                Estimate estimate = estimateRepository.findById(estimateId)
                                .orElseThrow(() -> new IllegalArgumentException(ESTIMATE_NOT_FOUND + estimateId));

                if (!estimate.canDecline()) {
                        throw new IllegalStateException(
                                        "Estimate cannot be declined in current state: " + estimate.getStatus());
                }

                estimate.setStatus(EstimateStatus.DECLINED);
                estimate.setDeclinedAt(LocalDateTime.now(clock));
                estimate.setDeclineReason(reason);

                // Set expiry date based on configuration
                ApprovalConfiguration config = getApprovalConfigurationById(estimate.getApprovalConfigurationId());
                estimate.setExpiresAt(LocalDateTime.now(clock).plusDays(config.getDeclineExpiryDays()));

                log.info("Estimate {} declined with reason: {}", estimateId, reason);
                return EstimateResponse.fromEntity(estimateRepository.save(estimate));
        }

        @Override
        @Transactional
        public EstimateResponse reopenEstimate(UUID estimateId) {
                Estimate estimate = estimateRepository.findById(estimateId)
                                .orElseThrow(() -> new IllegalArgumentException(ESTIMATE_NOT_FOUND + estimateId));

                ApprovalConfiguration config = getApprovalConfigurationById(estimate.getApprovalConfigurationId());

                if (!estimate.canReopen(config.getDeclineExpiryDays(), LocalDateTime.now(clock))) {
                        throw new IllegalStateException(
                                        "Estimate cannot be reopened - either not declined or expiry period has passed");
                }

                estimate.setStatus(EstimateStatus.DRAFT);
                estimate.setDeclinedAt(null);
                estimate.setDeclineReason(null);
                estimate.setExpiresAt(null);

                log.info("Estimate {} reopened from declined state", estimateId);
                return EstimateResponse.fromEntity(estimateRepository.save(estimate));
        }

        /**
         * Submit estimate for customer approval.
         * CAP:003 Issue #168 - Submit Estimate for Customer Approval
         * 
         * Validates completeness, creates immutable snapshot, and transitions
         * from DRAFT -> PENDING_APPROVAL.
         * 
         * @param estimateId estimate to submit
         * @param username   username submitting the estimate
         * @return updated estimate in PENDING_APPROVAL state
         * @throws IllegalArgumentException if estimate not found
         * @throws IllegalStateException    if estimate is not in DRAFT state or
         *                                  incomplete
         */
        @Override
        @Transactional
        public EstimateResponse submitForApproval(UUID estimateId, String username) {
                Estimate estimate = estimateRepository.findById(estimateId)
                                .orElseThrow(() -> new EntityNotFoundException(ESTIMATE_NOT_FOUND + estimateId));

                // Validate estimate is in correct state
                if (estimate.getStatus() != EstimateStatus.DRAFT) {
                        throw new IllegalStateException(
                                        "Cannot submit estimate - must be in DRAFT state, current state: "
                                                        + estimate.getStatus());
                }

                // Validate completeness
                validateEstimateCompleteness(estimate);

                // Create immutable snapshot before transitioning state
                createEstimateSnapshotInternal(estimateId, username, "Submitted for customer approval");

                // Transition to PENDING_APPROVAL
                estimate.setStatus(EstimateStatus.PENDING_APPROVAL);
                estimate.setSubmittedAt(LocalDateTime.now(clock));
                estimate.setSubmittedBy(username);

                // Set expiration based on approval configuration
                ApprovalConfiguration config = getApprovalConfigurationById(estimate.getApprovalConfigurationId());
                if (config.getApprovalWindowDays() != null && config.getApprovalWindowDays() > 0) {
                        estimate.setExpiresAt(LocalDateTime.now(clock).plusDays(config.getApprovalWindowDays()));
                }

                log.info("Estimate {} submitted for approval by user {}, expires at {}",
                                estimateId, username, estimate.getExpiresAt());
                return EstimateResponse.fromEntity(estimateRepository.save(estimate));
        }

        /**
         * Validate estimate has all required data to be submitted for approval.
         * 
         * @param estimate estimate to validate
         * @throws IllegalStateException if estimate is incomplete
         */
        private void validateEstimateCompleteness(Estimate estimate) {
                // Must have a customer
                if (estimate.getCustomerId() == null) {
                        throw new IllegalStateException("Cannot submit estimate - no customer assigned");
                }

                // Must have a vehicle
                if (estimate.getVehicleId() == null) {
                        throw new IllegalStateException("Cannot submit estimate - no vehicle assigned");
                }

                // Must have at least one line item
                List<EstimateItem> items = estimateItemRepository.findByEstimate_IdAndDeletedFalse(estimate.getId());
                if (items == null || items.isEmpty()) {
                        throw new IllegalStateException("Cannot submit estimate - no line items added");
                }

                // Must have calculated totals (subtotal should be non-null and positive)
                if (estimate.getSubtotal() == null || estimate.getSubtotal().signum() <= 0) {
                        throw new IllegalStateException(
                                        "Cannot submit estimate - totals not calculated. Call calculate endpoint first.");
                }

                log.debug("Estimate {} passed completeness validation - {} items, subtotal: {}",
                                estimate.getId(), items.size(), estimate.getSubtotal());
        }

        /**
         * Get the most specific approval configuration for a location and customer.
         * Returns default configuration if none found.
         */
        @Override
        public ApprovalConfiguration getApprovalConfiguration(UUID locationId, UUID customerId) {
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

        private ApprovalConfiguration getApprovalConfigurationById(UUID configId) {
                if (configId == null) {
                        return ApprovalConfiguration.builder()
                                        .approvalMethod(ApprovalConfiguration.ApprovalMethod.CLICK_CONFIRM)
                                        .declineExpiryDays(30)
                                        .requireSignature(false)
                                        .priority(0)
                                        .build();
                }
                return approvalConfigurationRepository.findById(configId)
                                .orElseThrow(() -> new IllegalArgumentException(
                                                "Approval configuration not found: " + configId));
        }

        @Override
        public void deleteEstimate(UUID id) {
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
         * @param username   username making the change
         * @return the updated estimate
         */
        @Override
        @Transactional
        public EstimateResponse updateEstimateFinancials(UUID estimateId, BigDecimal subtotal,
                        BigDecimal taxAmount, BigDecimal total,
                        String username) {
                return EstimateResponse.fromEntity(
                                updateEstimateFinancialsInternal(estimateId, subtotal, taxAmount, total, username));
        }

        /**
         * Internal helper for updating financial details. Used by public API and
         * internal calls.
         * Runs within the transaction context of the caller.
         */
        private Estimate updateEstimateFinancialsInternal(UUID estimateId, BigDecimal subtotal,
                        BigDecimal taxAmount, BigDecimal total, String username) {
                Estimate estimate = estimateRepository.findById(estimateId)
                                .orElseThrow(() -> new IllegalArgumentException(ESTIMATE_NOT_FOUND + estimateId));

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
                        publishEstimateRevisedEvents(saved, oldTotal, total, username);
                }

                return saved;
        }

        /**
         * Publish EstimateRevisedEvent for all Workorders associated with this
         * estimate.
         * This enables event-driven invalidation of approvals.
         */
        private void publishEstimateRevisedEvents(Estimate estimate, BigDecimal oldTotal,
                        BigDecimal newTotal, String username) {
                List<Workorder> workOrders = workOrderRepository.findAllByEstimate_Id(estimate.getId());

                log.info("Publishing EstimateRevisedEvent for {} Workorders linked to estimate {}",
                                workOrders.size(), estimate.getId());

                for (Workorder workOrder : workOrders) {
                        EstimateRevisedEvent event = EstimateRevisedEvent.builder()
                                        .estimateId(estimate.getId())
                                        .workorderId(workOrder.getId())
                                        .oldTotal(oldTotal != null ? oldTotal : BigDecimal.ZERO)
                                        .newTotal(newTotal != null ? newTotal : BigDecimal.ZERO)
                                        .changedBy(username)
                                        .timestamp(Instant.now(clock))
                                        .build();

                        eventPublisher.publishEvent(event);

                        log.info("Published EstimateRevisedEvent: estimateId={}, workOrderId={}, " +
                                        "oldTotal={}, newTotal={}",
                                        estimate.getId(), workOrder.getId(), oldTotal, newTotal);
                }
        }

        // ==================== ESTIMATE ITEM MANAGEMENT (CAP:002 Stories #14, #15, #17)
        // ====================

        /**
         * Add a line item (part or labor) to a draft estimate.
         * Story #14 (Add Parts) and #15 (Add Labor)
         *
         * @param estimateId estimate to add item to
         * @param request    item details
         * @param username   username adding the item
         * @return the created estimate item
         */
        @Override
        @Transactional
        @NonNull
        public EstimateItemResponse addEstimateItem(@NonNull UUID estimateId, @NonNull AddEstimateItemRequest request,
                        @NonNull String username) {
                Estimate estimate = estimateRepository.findById(estimateId)
                                .orElseThrow(() -> new IllegalArgumentException(ESTIMATE_NOT_FOUND + estimateId));

                // Validate estimate is in DRAFT status
                if (estimate.getStatus() != EstimateStatus.DRAFT) {
                        throw new IllegalStateException(
                                        "Cannot add items to estimate in status: " + estimate.getStatus()
                                                        + ". Estimate must be in DRAFT status.");
                }

                // Build and validate item
                EstimateItem item = EstimateItem.builder()
                                .estimate(estimate)
                                .itemType(request.getItemType())
                                .description(request.getDescription())
                                .quantity(request.getQuantity())
                                .unitPrice(request.getUnitPrice())
                                .taxCode(request.getTaxCode())
                                .productId(request.getProductId())
                                .serviceId(request.getServiceId())
                                .createdById(username)
                                .build();

                item.validate();
                EstimateItem saved = estimateItemRepository.save(item);

                log.info("Added {} item to estimate {}: itemId={}, description='{}'",
                                saved.getItemType(), estimateId, saved.getId(), saved.getDescription());

                return EstimateItemResponse.fromEntity(saved);
        }

        /**
         * Update an existing line item on a draft estimate.
         * Story #17 (Revise Estimate)
         *
         * @param estimateId estimate ID
         * @param itemId     item to update
         * @param request    updated fields
         * @return the updated item
         */
        @Override
        @Transactional
        @NonNull
        public EstimateItemResponse updateEstimateItem(@NonNull UUID estimateId, @NonNull UUID itemId,
                        @NonNull UpdateEstimateItemRequest request) {
                Estimate estimate = estimateRepository.findById(estimateId)
                                .orElseThrow(() -> new EntityNotFoundException(ESTIMATE_NOT_FOUND + estimateId));

                // Validate estimate is in DRAFT status
                if (estimate.getStatus() != EstimateStatus.DRAFT) {
                        throw new IllegalStateException(
                                        "Cannot update items in estimate with status: " + estimate.getStatus()
                                                        + ". Estimate must be in DRAFT status.");
                }

                EstimateItem item = estimateItemRepository.findByIdAndEstimate_IdAndDeletedFalse(itemId, estimateId)
                                .orElseThrow(() -> new EntityNotFoundException(
                                                "Item not found: " + itemId + " for estimate: " + estimateId));

                // Update only provided fields
                if (request.getDescription() != null) {
                        item.setDescription(request.getDescription());
                }
                if (request.getQuantity() != null) {
                        item.setQuantity(request.getQuantity());
                }
                if (request.getUnitPrice() != null) {
                        item.setUnitPrice(request.getUnitPrice());
                }
                if (request.getTaxCode() != null) {
                        item.setTaxCode(request.getTaxCode());
                }

                // Re-validate entity invariants after mutations
                item.validate();

                EstimateItem saved = estimateItemRepository.save(item);

                log.info("Updated item {} on estimate {}", itemId, estimateId);

                return EstimateItemResponse.fromEntity(saved);
        }

        /**
         * Remove a line item from a draft estimate (soft delete).
         * Story #17 (Revise Estimate)
         *
         * @param estimateId estimate ID
         * @param itemId     item to remove
         */
        @Override
        @Transactional
        public void deleteEstimateItem(@NonNull UUID estimateId, @NonNull UUID itemId) {
                Estimate estimate = estimateRepository.findById(estimateId)
                                .orElseThrow(() -> new IllegalArgumentException(ESTIMATE_NOT_FOUND + estimateId));

                // Validate estimate is in DRAFT status
                if (estimate.getStatus() != EstimateStatus.DRAFT) {
                        throw new IllegalStateException(
                                        "Cannot delete items from estimate with status: " + estimate.getStatus()
                                                        + ". Estimate must be in DRAFT status.");
                }

                EstimateItem item = estimateItemRepository.findByIdAndEstimate_IdAndDeletedFalse(itemId, estimateId)
                                .orElseThrow(() -> new IllegalArgumentException(
                                                "Item not found: " + itemId + " for estimate: " + estimateId));

                // Soft delete
                item.setDeleted(true);
                estimateItemRepository.save(item);

                log.info("Deleted (soft) item {} from estimate {}", itemId, estimateId);
        }

        /**
         * Get all line items for an estimate (excluding soft-deleted).
         */
        @Override
        @NonNull
        public List<EstimateItemResponse> getEstimateItems(@NonNull UUID estimateId) {
                return estimateItemRepository.findByEstimate_IdAndDeletedFalse(estimateId)
                                .stream()
                                .map(EstimateItemResponse::fromEntity)
                                .toList();
        }

        // ==================== TAX CALCULATION (CAP:002 Story #16) ====================

        /**
         * Calculate taxes and totals for an estimate based on its line items.
         * Story #16 (Calculate Taxes and Totals)
         * 
         * Integrates with pos-tax service for jurisdiction-based tax calculation.
         *
         * @param estimateId estimate to calculate
         * @param username   username requesting calculation
         * @return the updated estimate with calculated totals
         */
        @Override
        @Transactional
        @NonNull
        public EstimateResponse calculateEstimateTaxesAndTotals(@NonNull UUID estimateId, @NonNull String username) {
                Estimate estimate = estimateRepository.findById(estimateId)
                                .orElseThrow(() -> new IllegalArgumentException(ESTIMATE_NOT_FOUND + estimateId));

                // Validate estimate is in DRAFT status
                if (estimate.getStatus() != EstimateStatus.DRAFT) {
                        throw new IllegalStateException(
                                        "Cannot calculate totals for estimate in status: " + estimate.getStatus()
                                                        + ". Estimate must be in DRAFT status.");
                }

                // Get all line items
                List<EstimateItem> items = estimateItemRepository.findByEstimate_IdAndDeletedFalse(estimateId);

                if (items.isEmpty()) {
                        log.warn("No line items found for estimate {}, setting totals to zero", estimateId);
                        Estimate saved = updateEstimateFinancialsInternal(estimateId, BigDecimal.ZERO, BigDecimal.ZERO,
                                        BigDecimal.ZERO, username);
                        return EstimateResponse.fromEntity(saved);
                }

                // Build tax calculation request
                List<TaxLineItem> taxLineItems = items.stream()
                                .map(this::buildTaxLineItemFromEstimateItem)
                                .toList();

                // Get postal code from estimate's location
                // NOTE: Currently using default postal code. Should integrate with pos-location
                // service
                // to retrieve actual postal code for estimate.getLocationId()
                String postalCode = DEFAULT_POSTAL_CODE;

                TaxCalculationRequest taxRequest = TaxCalculationRequest.builder()
                                .lineItems(taxLineItems)
                                .locale("en-US")
                                .currencyCode("USD")
                                .destinationAddress(TaxAddress.builder()
                                                .countryCode("US")
                                                .regionCode("CA")
                                                .postalCode(postalCode)
                                                .build())

                                .customerId(estimate.getCustomerId().toString())
                                .referenceId(estimateId)
                                .referenceType(TaxReferenceType.ESTIMATE)
                                .build();

                BigDecimal subtotal;
                BigDecimal taxAmount;
                BigDecimal total;
                boolean testMode = false;

                try {
                        TaxCalculationResponse taxResponse = taxClient.calculateTax(taxRequest);
                        subtotal = taxResponse.getSubtotal();
                        taxAmount = taxResponse.getTotalTax();
                        total = taxResponse.getTotal();
                        testMode = taxResponse.isTestMode();
                } catch (RuntimeException ex) {
                        // Preserve contract behavior when tax service is unavailable by using
                        // deterministic fallback math.
                        subtotal = items.stream()
                                        .map(item -> {
                                                BigDecimal unitPrice = item.getUnitPrice() == null ? BigDecimal.ZERO
                                                                : item.getUnitPrice();
                                                BigDecimal quantity = item.getQuantity() == null ? BigDecimal.ZERO
                                                                : item.getQuantity();
                                                return unitPrice.multiply(quantity);
                                        })
                                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                                        .setScale(2, java.math.RoundingMode.HALF_UP);
                        taxAmount = subtotal.multiply(FALLBACK_TAX_RATE)
                                        .setScale(2, java.math.RoundingMode.HALF_UP);
                        total = subtotal.add(taxAmount)
                                        .setScale(2, java.math.RoundingMode.HALF_UP);
                        testMode = true;
                        log.warn("Tax service unavailable for estimate {}, using fallback tax rate {}",
                                        estimateId, FALLBACK_TAX_RATE, ex);
                }

                // Update estimate using internal helper (reuses transaction context)
                Estimate saved = updateEstimateFinancialsInternal(estimateId, subtotal, taxAmount, total, username);

                log.info("Calculated totals for estimate {}: subtotal={}, tax={}, total={} (testMode={})",
                                estimateId, subtotal, taxAmount, total, testMode);

                if (testMode) {
                        log.warn("Tax calculation for estimate {} used TEST MODE. "
                                        + "Verify tax service configuration for production use.", estimateId);
                }

                return EstimateResponse.fromEntity(saved);
        }

        /**
         * Map EstimateItemType to tax category code.
         * Different item types may have different tax treatment.
         */
        private String mapItemTypeToTaxCategory(EstimateItemType itemType) {
                if (itemType == null) {
                        return TAX_CATEGORY_GOODS;
                }
                return switch (itemType) {
                        case PART -> TAX_CATEGORY_GOODS;
                        case LABOR -> TAX_CATEGORY_SERVICES;
                };
        }

        /**
         * Get description for tax calculation purposes.
         * If the item has a description, use it. Otherwise, generate a fallback
         * description based on the item type and reference IDs.
         * 
         * @param item the estimate item
         * @return a non-null, non-blank description suitable for tax calculation
         */
        private String getDescriptionForTaxCalculation(EstimateItem item) {
                if (item.getDescription() != null && !item.getDescription().isBlank()) {
                        return item.getDescription();
                }

                // Generate fallback description based on item type and reference
                if (item.getItemType() == EstimateItemType.PART && item.getProductId() != null) {
                        return String.format("Part (Product ID: %s)", item.getProductId());
                } else if (item.getItemType() == EstimateItemType.LABOR && item.getServiceId() != null) {
                        return String.format("Labor (Service ID: %s)", item.getServiceId());
                } else {
                        // Fallback for items without description or reference (shouldn't happen due to
                        // validation)
                        String itemTypeName = item.getItemType() != null
                                        ? item.getItemType().name().toLowerCase().replace('_', ' ')
                                        : "unknown";
                        return String.format("%s item", itemTypeName);
                }
        }

        /**
         * Build a TaxLineItem from an EstimateItem for tax calculation.
         * 
         * @param item the estimate item
         * @return a TaxLineItem with appropriate description and tax category
         */
        private TaxLineItem buildTaxLineItemFromEstimateItem(EstimateItem item) {
                return TaxLineItem.builder()
                                .lineItemId(item.getId().toString())
                                .description(getDescriptionForTaxCalculation(item))
                                .quantity(item.getQuantity())
                                .unitPrice(item.getUnitPrice())
                                .taxCategory(mapItemTypeToTaxCategory(item.getItemType()))
                                .taxExempt(false)
                                .build();
        }

        // ==================== ESTIMATE SUMMARY (CAP:002 Story #18)
        // ====================

        /**
         * Get customer-facing summary of an estimate with grouped line items.
         * Story #18 (Present Estimate Summary)
         *
         * @param estimateId estimate ID
         * @return estimate with all line items grouped by type
         */
        @Override
        @NonNull
        public EstimateSummaryResponse getEstimateSummary(@NonNull UUID estimateId) {
                Estimate estimate = estimateRepository.findById(estimateId)
                                .orElseThrow(() -> new IllegalArgumentException(ESTIMATE_NOT_FOUND + estimateId));

                List<EstimateItem> items = estimateItemRepository.findByEstimate_IdAndDeletedFalse(estimateId);

                log.info("Retrieved summary for estimate {}: {} items", estimateId, items.size());

                return EstimateSummaryResponse.fromEstimateAndItems(estimate, items);
        }

        /**
         * Generate a PDF document for an estimate via pos-documents service.
         * Builds a Markdown representation of the estimate including header,
         * line items grouped by type, and financial totals, then renders it
         * as a PDF through the document service.
         *
         * @param estimateId estimate to generate PDF for
         * @return PDF content as byte array
         */
        @Override
        @NonNull
        public byte[] generateEstimatePdf(@NonNull UUID estimateId) {
                Estimate estimate = estimateRepository.findById(estimateId)
                                .orElseThrow(() -> new IllegalArgumentException(ESTIMATE_NOT_FOUND + estimateId));

                List<EstimateItem> items = estimateItemRepository.findByEstimate_IdAndDeletedFalse(estimateId);

                String markdown = buildEstimateMarkdown(estimate, items);

                log.info("Generating PDF for estimate {}, markdown length={}", estimateId, markdown.length());

                return documentClient.renderPdf(markdown);
        }

        /**
         * Build a Markdown document from estimate data and line items.
         * Groups items by type (parts vs labor) and includes financial summary.
         */
        private String buildEstimateMarkdown(@NonNull Estimate estimate, @NonNull List<EstimateItem> items) {
                var sb = new StringBuilder();

                // Header
                sb.append("# Estimate ").append(estimate.getEstimateNumber()).append("\n\n");
                sb.append("**Status:** ").append(estimate.getStatus()).append("  \n");
                sb.append("**Date:** ").append(estimate.getCreatedAt()).append("  \n");
                sb.append("**Customer ID:** ").append(estimate.getCustomerId()).append("  \n");
                sb.append("**Vehicle ID:** ").append(estimate.getVehicleId()).append("  \n");
                sb.append("**Location ID:** ").append(estimate.getLocationId()).append("  \n");

                if (estimate.getExpiresAt() != null) {
                        sb.append("**Expires:** ").append(estimate.getExpiresAt()).append("  \n");
                }
                sb.append("\n---\n\n");

                // Partition items by type
                List<EstimateItem> partItems = items.stream()
                                .filter(i -> i.getItemType() == EstimateItemType.PART)
                                .toList();
                List<EstimateItem> laborItems = items.stream()
                                .filter(i -> i.getItemType() == EstimateItemType.LABOR)
                                .toList();

                // Parts table
                if (!partItems.isEmpty()) {
                        sb.append("## Parts\n\n");
                        sb.append("| Description | Qty | Unit Price | Line Total |\n");
                        sb.append("|-------------|-----|------------|------------|\n");
                        for (EstimateItem item : partItems) {
                                appendItemRow(sb, item);
                        }
                        sb.append("\n");
                }

                // Labor table
                if (!laborItems.isEmpty()) {
                        sb.append("## Labor\n\n");
                        sb.append("| Description | Qty | Unit Price | Line Total |\n");
                        sb.append("|-------------|-----|------------|------------|\n");
                        for (EstimateItem item : laborItems) {
                                appendItemRow(sb, item);
                        }
                        sb.append("\n");
                }

                // Financial summary
                sb.append("---\n\n");
                sb.append("## Totals\n\n");
                sb.append("| | Amount |\n");
                sb.append("|---|--------|\n");
                sb.append("| **Subtotal** | ").append(formatAmount(estimate.getSubtotal())).append(" |\n");
                sb.append("| **Tax** | ").append(formatAmount(estimate.getTaxAmount())).append(" |\n");
                sb.append("| **Total** | ").append(formatAmount(estimate.getTotal())).append(" |\n");

                if (estimate.getCurrencyUomId() != null) {
                        sb.append("\n*Currency: ").append(estimate.getCurrencyUomId()).append("*\n");
                }

                return sb.toString();
        }

        /**
         * Append a single line item row to the Markdown table.
         */
        private void appendItemRow(@NonNull StringBuilder sb, @NonNull EstimateItem item) {
                BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ONE;
                BigDecimal price = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
                BigDecimal lineTotal = qty.multiply(price).setScale(2, java.math.RoundingMode.HALF_UP);

                sb.append("| ").append(item.getDescription() != null ? item.getDescription() : "—")
                                .append(" | ").append(qty)
                                .append(" | ").append(formatAmount(price))
                                .append(" | ").append(formatAmount(lineTotal))
                                .append(" |\n");
        }

        /**
         * Format a BigDecimal amount for display, with null handling.
         */
        private String formatAmount(BigDecimal amount) {
                return amount != null ? amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() : "0.00";
        }

        /**
         * Create an immutable snapshot of an estimate's complete state.
         * Story #18 (Present Estimate Summary) - Historical Snapshot
         *
         * @param estimateId estimate to snapshot
         * @param username   username creating snapshot
         * @param notes      optional notes about why snapshot was created
         * @return the created snapshot
         */
        @Override
        @Transactional
        @NonNull
        public EstimateSnapshotResponse createEstimateSnapshot(@NonNull UUID estimateId, @NonNull String username,
                        @Nullable String notes) {
                return EstimateSnapshotResponse.fromEntity(
                                createEstimateSnapshotInternal(estimateId, username, notes));
        }

        /**
         * Internal helper for creating estimate snapshots. Used by public API and
         * internal calls.
         * Runs within the transaction context of the caller.
         */
        private EstimateSnapshot createEstimateSnapshotInternal(@NonNull UUID estimateId, @NonNull String username,
                        @Nullable String notes) {
                Estimate estimate = estimateRepository.findById(estimateId)
                                .orElseThrow(() -> new IllegalArgumentException(ESTIMATE_NOT_FOUND + estimateId));

                List<EstimateItem> items = estimateItemRepository.findByEstimate_IdAndDeletedFalse(estimateId);

                // Serialize estimate + items to JSON (capturedAt stored in entity field only)
                Map<String, Object> snapshotData = new HashMap<>();
                snapshotData.put("estimate", estimate);
                snapshotData.put("items", items);

                String snapshotJson;
                try {
                        snapshotJson = objectMapper.writeValueAsString(snapshotData);
                } catch (Exception e) {
                        log.error("Failed to serialize estimate snapshot for estimateId={}", estimateId, e);
                        throw new IllegalStateException("Failed to create snapshot: JSON serialization error", e);
                }

                // Create snapshot entity
                EstimateSnapshot snapshot = EstimateSnapshot.builder()
                                .estimate(new Estimate(estimateId))
                                .status(estimate.getStatus())
                                .snapshotData(snapshotJson)
                                .capturedById(username)
                                .notes(notes)
                                .build();

                EstimateSnapshot saved = estimateSnapshotRepository.save(snapshot);

                log.info("Created snapshot for estimate {}: snapshotId={}, status={}, itemCount={}",
                                estimateId, saved.getId(), estimate.getStatus(), items.size());

                return saved;
        }

        /**
         * Find and expire estimates in PENDING_APPROVAL state that have exceeded their
         * approval window.
         * CAP:003 Issue #204 - Handle Approval Expiration
         * Called by scheduled job to mark expired estimates.
         * 
         * @return count of expired estimates
         */
        @Override
        @Transactional
        public int expirePendingApprovals() {
                LocalDateTime now = LocalDateTime.now(clock);
                log.debug("Checking for expired pending approvals at {}", now);

                // Find all estimates in PENDING_APPROVAL state that have expired
                List<Estimate> expiredEstimates = estimateRepository.findByStatusAndExpiresAtBefore(
                                EstimateStatus.PENDING_APPROVAL, now);

                if (expiredEstimates.isEmpty()) {
                        log.debug("No expired pending approvals found");
                        return 0;
                }

                log.info("Found {} expired pending approvals, marking them as expired", expiredEstimates.size());

                int successCount = 0;
                int errorCount = 0;

                for (Estimate estimate : expiredEstimates) {
                        try {
                                expireApproval(estimate, now);
                                successCount++;
                        } catch (Exception e) {
                                errorCount++;
                                log.error("Failed to expire approval for estimate {}: {}",
                                                estimate.getId(), e.getMessage(), e);
                        }
                }

                log.info("Approval expiration completed - success: {}, errors: {}", successCount, errorCount);
                return successCount;
        }

        @Override
        @Transactional
        @NonNull
        public CreateEstimateFromAppointmentResponse createEstimateFromAppointment(
                        @NonNull CreateEstimateFromAppointmentRequest request) {
                if (request.getAppointmentId() == null) {
                        throw new IllegalArgumentException("appointmentId is required");
                }
                if (request.getCustomerId() == null) {
                        throw new IllegalArgumentException("customerId is required");
                }

                log.info("Received createEstimateFromAppointment: appointmentId={}, idempotencyKey={}",
                                maskForLog(request.getAppointmentId()),
                                maskForLog(request.getIdempotencyKey()));

                Optional<Estimate> existingEstimate = estimateRepository
                                .findByAppointmentId(request.getAppointmentId());
                if (existingEstimate.isPresent()) {
                        Estimate existing = existingEstimate.get();
                        log.info("Idempotency hit: returning existing estimateId={} for appointmentId={}",
                                        maskForLog(existing.getId()),
                                        maskForLog(request.getAppointmentId()));
                        return CreateEstimateFromAppointmentResponse.builder()
                                        .estimateId(existing.getId())
                                        .status(existing.getStatus() != null ? existing.getStatus().name()
                                                        : EstimateStatus.DRAFT.name())
                                        .created(false)
                                        .build();
                }

                Estimate estimate = Estimate.builder()
                                .status(EstimateStatus.DRAFT)
                                .customerId(request.getCustomerId())
                                .vehicleId(request.getVehicleId())
                                .locationId(request.getLocationId())
                                .appointmentId(request.getAppointmentId())
                                .build();

                Estimate saved = estimateRepository.save(estimate);
                UUID estimateId = saved.getId();

                log.info("Estimate created from appointment: estimateId={}, appointmentId={}",
                                maskForLog(saved.getId()),
                                maskForLog(request.getAppointmentId()));

                return CreateEstimateFromAppointmentResponse.builder()
                                .estimateId(estimateId)
                                .status(EstimateStatus.DRAFT.name())
                                .created(true)
                                .build();
        }

        private String maskForLog(Object value) {
                if (value == null) {
                        return "null";
                }
                String sanitized = value.toString()
                                .replace('\r', '_')
                                .replace('\n', '_')
                                .replace('\t', '_');
                int length = sanitized.length();
                if (length <= 4) {
                        return "****";
                }
                return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
        }

        /**
         * Mark an estimate as expired due to approval window timeout.
         * 
         * @param estimate estimate to expire
         * @param now      current timestamp
         */
        private void expireApproval(@NonNull Estimate estimate, @NonNull LocalDateTime now) {
                log.info("Expiring approval for estimate {} (expired at: {}, now: {})",
                                estimate.getId(), estimate.getExpiresAt(), now);

                estimate.setStatus(EstimateStatus.EXPIRED);
                estimate.setDeclineReason("Approval window expired - customer did not respond within "
                                + "the approval window that ended at " + estimate.getExpiresAt());
                estimate.setDeclinedAt(now);

                estimateRepository.save(estimate);

                log.info("Estimate {} marked as EXPIRED due to approval timeout", estimate.getId());
        }
}
