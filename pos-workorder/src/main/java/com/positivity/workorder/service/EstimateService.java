package com.positivity.workorder.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

public interface EstimateService {

        List<EstimateResponse> getAllEstimates();

        Optional<EstimateResponse> getEstimateById(UUID id);

        List<EstimateResponse> getEstimatesByCustomer(UUID customerId);

        List<EstimateResponse> getEstimatesByLocation(UUID locationId);

        /**
         * Paginated search for estimates by optional customer and/or vehicle filter.
         * At least one of {@code customerId} or {@code vehicleId} should be provided;
         * if both are {@code null} all estimates are returned (unfiltered).
         *
         * @param customerId filter by customer UUID (optional)
         * @param vehicleId  filter by vehicle UUID (optional)
         * @param pageable   pagination and sorting configuration
         * @return page of estimate summaries
         */
        @NonNull
        Page<EstimateSummaryResponse> searchEstimates(
                        @Nullable UUID customerId,
                        @Nullable UUID vehicleId,
                        @NonNull Pageable pageable);

        /**
         * Create a new draft estimate with proper validation and defaulting
         * 
         * @param request         The create estimate request with customer and vehicle
         *                        IDs
         * @param createdByUserId The user ID creating the estimate
         * @return The created estimate DTO
         * @throws IllegalArgumentException if validation fails
         */
        EstimateResponse createEstimate(CreateEstimateRequest request, String username);

        EstimateResponse approveEstimate(UUID estimateId, UUID approvedByCustomerId);

        EstimateResponse approveEstimate(UUID estimateId, UUID customerId, String signatureData,
                        String signatureMimeType, String signerName, String notes);

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
        EstimateResponse approveEstimate(UUID estimateId, UUID customerId, String signatureData,
                        String signatureMimeType, String signerName, String notes,
                        String purchaseOrderNumber,
                        List<com.positivity.workorder.internal.dto.LineItemApprovalDto> lineItemApprovals);

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
        EstimateResponse approveEstimate(UUID estimateId, UUID customerId, String signatureData,
                        String signatureMimeType, String signerName, String notes,
                        String purchaseOrderNumber);

        EstimateResponse declineEstimate(UUID estimateId, String reason);

        EstimateResponse reopenEstimate(UUID estimateId);

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
        EstimateResponse submitForApproval(UUID estimateId, String username);

        /**
         * Get the most specific approval configuration for a location and customer.
         * Returns default configuration if none found.
         */
        ApprovalConfiguration getApprovalConfiguration(UUID locationId, UUID customerId);

        void deleteEstimate(UUID id);

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
        EstimateResponse updateEstimateFinancials(UUID estimateId, BigDecimal subtotal,
                        BigDecimal taxAmount, BigDecimal total,
                        String username);

        /**
         * Add a line item (part or labor) to a draft estimate.
         * Story #14 (Add Parts) and #15 (Add Labor)
         *
         * @param estimateId estimate to add item to
         * @param request    item details
         * @param username   username adding the item
         * @return the created estimate item
         */
        EstimateItemResponse addEstimateItem(UUID estimateId, AddEstimateItemRequest request,
                        String username);

        /**
         * Update an existing line item on a draft estimate.
         * Story #17 (Revise Estimate)
         *
         * @param estimateId estimate ID
         * @param itemId     item to update
         * @param request    updated fields
         * @return the updated item
         */
        EstimateItemResponse updateEstimateItem(UUID estimateId, UUID itemId,
                        UpdateEstimateItemRequest request);

        /**
         * Remove a line item from a draft estimate (soft delete).
         * Story #17 (Revise Estimate)
         *
         * @param estimateId estimate ID
         * @param itemId     item to remove
         */
        void deleteEstimateItem(UUID estimateId, UUID itemId);

        /**
         * Get all line items for an estimate (excluding soft-deleted).
         */
        List<EstimateItemResponse> getEstimateItems(UUID estimateId);

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
        EstimateResponse calculateEstimateTaxesAndTotals(UUID estimateId, String username);

        /**
         * Get customer-facing summary of an estimate with grouped line items.
         * Story #18 (Present Estimate Summary)
         *
         * @param estimateId estimate ID
         * @return estimate with all line items grouped by type
         */
        EstimateSummaryResponse getEstimateSummary(UUID estimateId);

        /**
         * Create an immutable snapshot of an estimate's complete state.
         * Story #18 (Present Estimate Summary) - Historical Snapshot
         *
         * @param estimateId estimate to snapshot
         * @param username   username creating snapshot
         * @param notes      optional notes about why snapshot was created
         * @return the created snapshot
         */
        EstimateSnapshotResponse createEstimateSnapshot(UUID estimateId, String username,
                        String notes);

        /**
         * Generate a PDF document for an estimate.
         * Renders estimate details and line items as a PDF via pos-documents service.
         *
         * @param estimateId estimate to generate PDF for
         * @return PDF content as byte array
         */
        @NonNull
        byte[] generateEstimatePdf(@NonNull UUID estimateId);

        /**
         * Find and expire estimates in PENDING_APPROVAL state that have exceeded their
         * approval window.
         * CAP:003 Issue #204 - Handle Approval Expiration
         * Called by scheduled job to mark expired estimates.
         * 
         * @return count of expired estimates
         */
        int expirePendingApprovals();

        /**
         * Creates a new DRAFT estimate from an appointment, or returns the existing
         * estimate if one already exists for the given appointmentId (idempotent).
         * CAP:140 Story #65.
         *
         * @param request the create request including appointmentId, customerId,
         *                vehicleId, locationId
         * @return response containing estimateId and status
         */
        @NonNull
        CreateEstimateFromAppointmentResponse createEstimateFromAppointment(
                        @NonNull CreateEstimateFromAppointmentRequest request);

}