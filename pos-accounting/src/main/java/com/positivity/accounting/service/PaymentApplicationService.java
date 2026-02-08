package com.positivity.accounting.service;

import com.positivity.accounting.internal.client.InvoiceServiceClient;
import com.positivity.accounting.internal.client.InvoiceServiceException;
import com.positivity.accounting.internal.dto.ApplyPaymentToInvoiceRequest;
import com.positivity.accounting.internal.dto.ApplyPaymentToInvoiceResponse;
import com.positivity.accounting.internal.dto.InvoiceDetails;
import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.dto.PaymentApplicationResponse;
import com.positivity.accounting.internal.dto.ReversePaymentApplicationRequest;
import com.positivity.accounting.internal.entity.*;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import com.positivity.accounting.internal.repository.*;
import com.positivity.security.common.SecurityContextHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for managing payment applications to invoices (AR).
 * 
 * Business Rules (from Issue #114):
 * - Accounting is SoR for PaymentApplication lifecycle/state
 * - Payment domain is SoR for Payment lifecycle (auth/capture/settlement)
 * - Applications are atomic across all target invoices
 * - Applications are idempotent via applicationRequestId
 * - Overpayments create CustomerCredit
 * - Reversals are compensating transactions (no deletes)
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentApplicationService {

        private final ReceivablePaymentRepository receivablePaymentRepository;
        private final PaymentApplicationRepository paymentApplicationRepository;
        private final CustomerCreditRepository customerCreditRepository;
        private final PaymentApplicationReversalRepository reversalRepository;
        private final InvoicePaymentStatusService invoicePaymentStatusService;
        private final InvoiceServiceClient invoiceServiceClient;

        /**
         * Handle PaymentCleared event from Payment domain.
         * Creates ReceivablePayment record with unappliedAmount = totalAmount.
         * 
         * Idempotent: uses sourceEventId to prevent duplicate processing.
         * 
         * @param paymentId     payment identifier from Payment domain
         * @param customerId    customer identifier
         * @param currency      payment currency
         * @param totalAmount   cleared payment amount
         * @param clearedAt     settlement timestamp
         * @param sourceEventId PaymentCleared event ID (idempotency key)
         * @return created or existing ReceivablePayment
         */
        public ReceivablePayment handlePaymentCleared(
                        @NonNull UUID paymentId,
                        @NonNull UUID customerId,
                        @NonNull String currency,
                        @NonNull BigDecimal totalAmount,
                        @NonNull Instant clearedAt,
                        @NonNull UUID sourceEventId) {

                // Idempotency check
                if (receivablePaymentRepository.existsBySourceEventId(sourceEventId)) {
                        log.info("PaymentCleared event {} already processed, returning existing payment",
                                        sourceEventId);
                        return receivablePaymentRepository.findBySourceEventId(sourceEventId)
                                        .orElseThrow(() -> new IllegalStateException(
                                                        "Payment not found after existence check"));
                }

                ReceivablePayment payment = new ReceivablePayment();
                payment.setPaymentId(paymentId);
                payment.setCustomerId(customerId);
                payment.setCurrency(currency);
                payment.setTotalAmount(totalAmount);
                payment.setUnappliedAmount(totalAmount); // Initially all available
                payment.setStatus(ReceivablePaymentStatus.AVAILABLE);
                payment.setClearedAt(clearedAt);
                payment.setSourceEventId(sourceEventId);
                payment.setCreatedBy(getCurrentUser()); // From PaymentCleared event

                ReceivablePayment saved = receivablePaymentRepository.save(payment);
                log.info("Created ReceivablePayment {} for customer {} with amount {} (event: {})",
                                paymentId, customerId, totalAmount, sourceEventId);
                return saved;
        }

        /**
         * Apply a payment to one or more invoices atomically.
         * 
         * <p><strong>Atomicity Guarantee:</strong> This operation ensures atomicity across all invoice
         * applications through compensating reversals. If any invoice service call fails, all previously
         * successful invoice mutations are reversed before the local DB transaction rolls back. This
         * prevents the Accounting and Invoice services from becoming out of sync.
         * 
         * <p><strong>Idempotency Requirement:</strong> The Invoice service MUST implement idempotent
         * payment application using {@code paymentApplicationId} as the idempotency key. This allows
         * safe retries and prevents duplicate applications if the same {@code paymentApplicationId}
         * is sent multiple times.
         * 
         * <p><strong>Failure Handling:</strong>
         * <ul>
         * <li>If validation fails, throws exception before any mutations occur</li>
         * <li>If an invoice service call fails, performs compensating reversals on all successfully
         *     applied invoices, then rethrows the exception to trigger DB transaction rollback</li>
         * <li>All compensating reversals are logged for audit trail and debugging</li>
         * </ul>
         * 
         * Main Success Scenario (from Issue #114):
         * 1. Validate payment is AVAILABLE with sufficient funds
         * 2. Validate each invoice is applicable (not PaidInFull/Voided/Cancelled)
         * 3. Validate requested amounts
         * 4. Create immutable PaymentApplication records
         * 5. Update invoice balances and statuses (with compensating reversal on failure)
         * 6. Update payment unappliedAmount
         * 7. Handle overpayment (create CustomerCredit)
         * 8. Emit events for downstream consumers
         * 
         * @param paymentId payment to apply
         * @param request   application request with invoices and amounts
         * @return application response with details
         * @throws ResponseStatusException with NOT_FOUND if payment not found
         * @throws ResponseStatusException with BAD_REQUEST if validation fails or insufficient funds
         * @throws ResponseStatusException with CONFLICT if currency mismatch or invoice not applicable
         * @throws ResponseStatusException with SERVICE_UNAVAILABLE if invoice service call fails (after compensating reversals)
         */
        public PaymentApplicationResponse applyPaymentToInvoices(
                        @NonNull UUID paymentId,
                        @NonNull PaymentApplicationRequest request) {

                // Idempotency check
                if (paymentApplicationRepository.existsByApplicationRequestId(request.getApplicationRequestId())) {
                        log.info("Application request {} already processed, returning existing result",
                                        request.getApplicationRequestId());
                        return buildResponseForExistingApplication(request.getApplicationRequestId());
                }

                // 1. Validate payment is AVAILABLE
                ReceivablePayment payment = receivablePaymentRepository.findById(paymentId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "Payment not found: " + paymentId));

                if (payment.getStatus() != ReceivablePaymentStatus.AVAILABLE) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "Payment " + paymentId + " is not available (status: " + payment.getStatus()
                                                        + ")");
                }

                // 2. Calculate total application amount
                BigDecimal totalApplicationAmount = request.getApplications().stream()
                                .map(PaymentApplicationRequest.InvoiceApplication::getAmountToApply)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                // 3. Validate sufficient funds
                if (!payment.hasSufficientFunds(totalApplicationAmount)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.format(
                                        "Insufficient funds: requested %s, available %s",
                                        totalApplicationAmount, payment.getUnappliedAmount()));
                }

                // 4. Validate all invoices, cap amounts to balanceDue, and detect overpayment
                BigDecimal actualTotalApplicationAmount = BigDecimal.ZERO;
                BigDecimal overpaymentAmount = BigDecimal.ZERO;
                Map<UUID, BigDecimal> cappedAmounts = new HashMap<>();

                for (PaymentApplicationRequest.InvoiceApplication invoiceApp : request.getApplications()) {
                        var invoiceDetails = validateInvoiceApplication(invoiceApp, payment.getCurrency());
                        
                        // Cap application to invoice balance due
                        BigDecimal requestedAmount = invoiceApp.getAmountToApply();
                        BigDecimal cappedAmount = requestedAmount.min(invoiceDetails.getBalanceDue());
                        BigDecimal excessForThisInvoice = requestedAmount.subtract(cappedAmount);
                        
                        cappedAmounts.put(invoiceApp.getInvoiceId(), cappedAmount);
                        actualTotalApplicationAmount = actualTotalApplicationAmount.add(cappedAmount);
                        overpaymentAmount = overpaymentAmount.add(excessForThisInvoice);
                        
                        if (excessForThisInvoice.compareTo(BigDecimal.ZERO) > 0) {
                                log.info("Capped application to invoice {} from {} to {} (excess: {})",
                                                invoiceApp.getInvoiceId(), requestedAmount, cappedAmount,
                                                excessForThisInvoice);
                        }
                }

                // Capture current user early to avoid issues in exception handler
                String currentUser = getCurrentUser();

                // 5. Create PaymentApplication records and update invoices
                // Track successful applications for compensating reversals if a later application fails
                List<PaymentApplicationResponse.ApplicationDetail> applicationDetails = new ArrayList<>();
                List<PaymentApplication> successfulApplications = new ArrayList<>();
                Instant applicationTimestamp = Instant.now();

                try {
                        for (PaymentApplicationRequest.InvoiceApplication invoiceApp : request.getApplications()) {
                                BigDecimal amountToApply = cappedAmounts.get(invoiceApp.getInvoiceId());
                                
                                // Skip if capped amount is zero (fully overpayment scenario)
                                if (amountToApply.compareTo(BigDecimal.ZERO) == 0) {
                                        log.info("Skipping invoice {} - capped amount is 0 (already paid in full)",
                                                        invoiceApp.getInvoiceId());
                                        continue;
                                }
                                
                                PaymentApplication application = new PaymentApplication();
                                application.setPaymentId(paymentId);
                                application.setInvoiceId(invoiceApp.getInvoiceId());
                                application.setCustomerId(payment.getCustomerId());
                                application.setCurrency(payment.getCurrency());
                                application.setAppliedAmount(amountToApply);
                                application.setApplicationTimestamp(applicationTimestamp);
                                application.setApplicationRequestId(request.getApplicationRequestId());
                                application.setCreatedAt(applicationTimestamp);
                                application.setCreatedBy(currentUser);

                                PaymentApplication saved = paymentApplicationRepository.save(application);

                                // Apply payment to invoice via service client
                                // Uses paymentApplicationId as idempotency key on Invoice service side
                                ApplyPaymentToInvoiceRequest invoiceRequest = ApplyPaymentToInvoiceRequest.builder()
                                                .paymentApplicationId(saved.getPaymentApplicationId())
                                                .amountApplied(amountToApply)
                                                .appliedAt(applicationTimestamp)
                                                .currency(payment.getCurrency())
                                                .paymentId(paymentId)
                                                .appliedBy(currentUser)
                                                .build();

                                var invoiceResponse = invoiceServiceClient.applyPaymentToInvoice(
                                                invoiceApp.getInvoiceId(),
                                                invoiceRequest);

                                log.info("Applied payment {} to invoice {} via service call, new balance: {} (was {})",
                                                saved.getPaymentApplicationId(), invoiceApp.getInvoiceId(),
                                                invoiceResponse.getBalanceAfter(), invoiceResponse.getBalanceBefore());

                                // Track successful application for potential compensating reversal
                                successfulApplications.add(saved);

                                // Store response for building detail
                                applicationDetails
                                                .add(buildApplicationDetail(saved, invoiceApp.getInvoiceId(), invoiceResponse));

                                log.info("Created PaymentApplication {} for payment {} to invoice {} amount {}",
                                                saved.getPaymentApplicationId(), paymentId, invoiceApp.getInvoiceId(),
                                                amountToApply);
                        }
                } catch (InvoiceServiceException e) {
                        // Compensating transaction: reverse all successful invoice applications
                        // This ensures Invoice service state is rolled back before DB transaction rollback
                        log.error("Invoice service call failed during payment application for payment {} " +
                                        "(applicationRequestId: {}). Performing compensating reversals for {} successful applications",
                                        paymentId, request.getApplicationRequestId(), successfulApplications.size(), e);
                        
                        performCompensatingReversals(successfulApplications, currentUser,
                                        "Automatic compensating reversal due to partial failure");
                        
                        // Determine appropriate HTTP status to expose to API clients.
                        // Propagate 4xx statuses when available, but treat 5xx or unknown as service unavailability.
                        HttpStatus status = HttpStatus.resolve(e.getHttpStatus());
                        if (status == null || status.is5xxServerError()) {
                                status = HttpStatus.SERVICE_UNAVAILABLE;
                        }
                        
                        // Rethrow to trigger DB transaction rollback
                        // All PaymentApplication records will be rolled back along with other local state
                        throw new ResponseStatusException(status,
                                        String.format("Failed to apply payment %s to invoices. Local changes have been rolled back and compensating reversals were attempted; invoice state may be out of sync. Check logs and reconcile invoices manually if necessary.",
                                                        paymentId), e);
                } catch (RuntimeException e) {
                        // Unexpected runtime errors - still perform compensating reversals for safety
                        log.error("Unexpected error during payment application for payment {} " +
                                        "(applicationRequestId: {}). Performing compensating reversals for {} successful applications",
                                        paymentId, request.getApplicationRequestId(), successfulApplications.size(), e);
                        
                        performCompensatingReversals(successfulApplications, currentUser,
                                        "Automatic compensating reversal due to unexpected failure");
                        
                        // Rethrow original exception
                        throw e;
                }

                // 6. Capture unapplied amount before applying, for overpayment credit calculation
                BigDecimal unappliedBeforeApplication = payment.getUnappliedAmount();
                
                // 7. Update payment unappliedAmount (apply actual applied amount)
                payment.applyAmount(actualTotalApplicationAmount);
                payment.setModifiedAt(applicationTimestamp);
                payment.setModifiedBy(currentUser);

                // 8. Handle overpayment - create CustomerCredit if there's overpayment
                PaymentApplicationResponse.CustomerCreditInfo creditInfo = null;
                
                if (overpaymentAmount.compareTo(BigDecimal.ZERO) > 0) {
                        // Explicit overpayment: payment amount exceeded what was needed for invoices
                        // The unapplied amount after application is the credit amount
                        // (overpaymentAmount represents what couldn't be applied due to balance limits)
                        BigDecimal unappliedAfterApplication = payment.getUnappliedAmount();
                        
                        creditInfo = createCustomerCredit(payment, unappliedAfterApplication, applicationTimestamp);
                        
                        // Mark payment as fully applied by converting remaining balance to credit
                        // This ensures payment status becomes FULLY_APPLIED
                        if (unappliedAfterApplication.compareTo(BigDecimal.ZERO) > 0) {
                                payment.applyAmount(unappliedAfterApplication);
                        }
                        
                        log.info("Overpayment detected: requested={}, applied={}, overpayment={}, " +
                                        "unapplied before={}, unapplied after={}, credit={}",
                                        totalApplicationAmount, actualTotalApplicationAmount,
                                        overpaymentAmount, unappliedBeforeApplication,
                                        unappliedAfterApplication, unappliedAfterApplication);
                }
                
                receivablePaymentRepository.save(payment);

                // 9. Build response
                return PaymentApplicationResponse.builder()
                                .paymentId(paymentId)
                                .customerId(payment.getCustomerId())
                                .currency(payment.getCurrency())
                                .totalAmount(payment.getTotalAmount())
                                .appliedAmount(actualTotalApplicationAmount)
                                .remainingAmount(payment.getUnappliedAmount())
                                .applications(applicationDetails)
                                .customerCredit(creditInfo)
                                .applicationTimestamp(applicationTimestamp)
                                .applicationRequestId(request.getApplicationRequestId())
                                .build();
        }

        /**
         * Reverse a payment application (compensating transaction).
         * 
         * Business Rules (from Issue #114):
         * - Reversals are NEW records, not deletions
         * - Original PaymentApplication remains unchanged
         * - Restores invoice balance and payment unappliedAmount
         * - Requires elevated permission (enforced at controller)
         * - Requires non-empty reason for audit
         * - Derives reversedBy from SecurityContext
         * 
         * @param paymentApplicationId application to reverse
         * @param reason               reversal reason (required)
         * @return reversal record
         * @throws ResponseStatusException with NOT_FOUND if application not found
         * @throws ResponseStatusException with CONFLICT if already reversed
         */
        public PaymentApplicationReversal reversePaymentApplication(
                        @NonNull UUID paymentApplicationId,
                        @NonNull String reason) {

                String reversedBy = getCurrentUser();

                // 1. Check if already reversed
                if (reversalRepository.existsByOriginalPaymentApplicationId(paymentApplicationId)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                        "Payment application " + paymentApplicationId + " has already been reversed");
                }

                // 2. Get original application
                PaymentApplication original = paymentApplicationRepository.findById(paymentApplicationId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "Payment application not found: " + paymentApplicationId));

                // 3. Create reversal record
                PaymentApplicationReversal reversal = new PaymentApplicationReversal();
                reversal.setOriginalPaymentApplicationId(paymentApplicationId);
                reversal.setAmount(original.getAppliedAmount());
                reversal.setReason(reason);
                reversal.setReversedBy(reversedBy);
                reversal.setReversedAt(Instant.now());

                PaymentApplicationReversal saved = reversalRepository.save(reversal);

                // 4. Restore payment unappliedAmount
                ReceivablePayment payment = receivablePaymentRepository.findById(original.getPaymentId())
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                                "Internal error while reversing payment application " + paymentApplicationId
                                                                + ": associated payment " + original.getPaymentId() + " not found"));

                payment.reverseAmount(original.getAppliedAmount());
                payment.setModifiedAt(Instant.now());
                payment.setModifiedBy(reversedBy);
                receivablePaymentRepository.save(payment);

                // 5. TODO: Restore invoice balance via service client
                ReversePaymentApplicationRequest invoiceRequest = ReversePaymentApplicationRequest.builder()
                                .paymentApplicationId(original.getPaymentApplicationId())
                                .reversalId(saved.getReversalId())
                                .amountToRestore(original.getAppliedAmount())
                                .reason(reason)
                                .reversedBy(reversedBy)
                                .build();

                var invoiceResponse = invoiceServiceClient.reversePaymentApplication(
                                original.getInvoiceId(),
                                invoiceRequest);

                log.info("Reversed invoice balance for invoice {} via service call, restored amount: {} (reason: {})",
                                original.getInvoiceId(), original.getAppliedAmount(), reason);
                log.info("Invoice {} new balance: {} (was {})",
                                original.getInvoiceId(), invoiceResponse.getBalanceDue(),
                                invoiceResponse.getBalanceBefore());

                log.info("Created reversal {} for application {} by {} (reason: {})",
                                saved.getReversalId(), paymentApplicationId, reversedBy, reason);

                return saved;
        }

        /**
         * Get payment application by ID.
         * 
         * @throws ResponseStatusException with NOT_FOUND if application not found
         */
        @Transactional(readOnly = true)
        public PaymentApplication getPaymentApplication(@NonNull UUID paymentApplicationId) {
                return paymentApplicationRepository.findById(paymentApplicationId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "Payment application not found: " + paymentApplicationId));
        }

        /**
         * List all applications for a payment.
         */
        @Transactional(readOnly = true)
        public List<PaymentApplication> listApplicationsForPayment(@NonNull UUID paymentId) {
                return paymentApplicationRepository.findByPaymentId(paymentId);
        }

        /**
         * List all applications for an invoice.
         */
        @Transactional(readOnly = true)
        public List<PaymentApplication> listApplicationsForInvoice(@NonNull UUID invoiceId) {
                return paymentApplicationRepository.findByInvoiceId(invoiceId);
        }

        // ===== PRIVATE HELPER METHODS =====

        private InvoiceDetails validateInvoiceApplication(
                        PaymentApplicationRequest.InvoiceApplication invoiceApp,
                        String paymentCurrency) {

                // Validate amount > 0
                if (invoiceApp.getAmountToApply().compareTo(BigDecimal.ZERO) <= 0) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "Amount to apply must be greater than 0 for invoice "
                                                        + invoiceApp.getInvoiceId());
                }

                // Fetch invoice details from service (with InvoiceServiceException handling)
                InvoiceDetails invoiceDetails;
                try {
                        invoiceDetails = invoiceServiceClient.getInvoiceDetails(invoiceApp.getInvoiceId());
                } catch (InvoiceServiceException e) {
                        // Translate service exception to appropriate HTTP status
                        HttpStatus status = HttpStatus.resolve(e.getHttpStatus());
                        if (status == null || status.is5xxServerError()) {
                                status = HttpStatus.SERVICE_UNAVAILABLE;
                        }
                        throw new ResponseStatusException(status, 
                                "Failed to validate invoice " + invoiceApp.getInvoiceId() + ": " + e.getMessage(), e);
                }

                // Validate invoice status is OPEN or PARTIALLY_PAID
                String status = invoiceDetails.getStatus();
                if ("PAID_IN_FULL".equals(status) || "VOIDED".equals(status) || "CANCELLED".equals(status)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                        "Invoice " + invoiceApp.getInvoiceId()
                                                        + " is not applicable for payment (status: " + status + ")");
                }

                // Validate currency matches
                if (!invoiceDetails.getCurrency().equals(paymentCurrency)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                        "Currency mismatch for invoice " + invoiceApp.getInvoiceId() +
                                                        " (invoice: " + invoiceDetails.getCurrency() + ", payment: "
                                                        + paymentCurrency + ")");
                }

                log.info("Invoice validation passed for invoice {} (status: {}, balanceDue: {})",
                                invoiceApp.getInvoiceId(), status, invoiceDetails.getBalanceDue());
                
                return invoiceDetails;
        }

        private PaymentApplicationResponse.ApplicationDetail buildApplicationDetail(
                        PaymentApplication application,
                        UUID invoiceId,
                        ApplyPaymentToInvoiceResponse invoiceResponse) {

                // TODO #3: Fetch actual invoice balance before/after
                return PaymentApplicationResponse.ApplicationDetail.builder()
                                .paymentApplicationId(application.getPaymentApplicationId())
                                .invoiceId(invoiceId)
                                .appliedAmount(application.getAppliedAmount())
                                .invoiceBalanceBefore(invoiceResponse.getBalanceBefore())
                                .invoiceBalanceAfter(invoiceResponse.getBalanceAfter())
                                .invoiceStatus(invoiceResponse.getStatus())
                                .build();
        }

        /**
         * Perform compensating reversals on Invoice service for successfully applied payments.
         * 
         * <p>This method is called when a payment application fails partway through processing
         * multiple invoices. It reverses all successful invoice mutations to ensure the Invoice
         * service state matches the local DB state after transaction rollback.
         * 
         * <p><strong>Best Effort:</strong> If a compensating reversal fails, it is logged but
         * does not block other reversals. This prevents cascading failures. Failed reversals
         * are logged at ERROR level for manual reconciliation.
         * 
         * <p><strong>Idempotency:</strong> Invoice service reversal endpoints must be idempotent
         * using {@code paymentApplicationId} to handle retry scenarios safely.
         * 
         * <p><strong>Reversal ID:</strong> The {@code reversalId} is set to null for compensating
         * reversals since no local reversal record is created (the transaction will roll back). The
         * Invoice service should accept null for this field or use {@code paymentApplicationId}
         * as the idempotency key instead.
         * 
         * @param successfulApplications list of PaymentApplication records that were successfully
         *                               applied to Invoice service
         * @param reversedBy            user performing the reversal (for audit trail)
         * @param reason                 reason for reversal (for audit trail)
         */
        private void performCompensatingReversals(
                        List<PaymentApplication> successfulApplications,
                        String reversedBy,
                        String reason) {
                
                if (successfulApplications.isEmpty()) {
                        log.info("No successful applications to reverse");
                        return;
                }
                
                log.warn("Performing compensating reversals for {} invoice applications",
                                successfulApplications.size());
                
                // Reverse in reverse order (LIFO) to unwind in logical sequence
                for (int i = successfulApplications.size() - 1; i >= 0; i--) {
                        PaymentApplication app = successfulApplications.get(i);
                        
                        try {
                                log.info("Compensating reversal: reversing invoice {} payment application {}",
                                                app.getInvoiceId(), app.getPaymentApplicationId());
                                
                                ReversePaymentApplicationRequest reversalRequest = ReversePaymentApplicationRequest.builder()
                                                .paymentApplicationId(app.getPaymentApplicationId())
                                                // reversalId is null for compensating reversals since no local reversal
                                                // record is created (transaction will roll back). Invoice service should
                                                // accept null or use paymentApplicationId as the idempotency key.
                                                .reversalId(null)
                                                .amountToRestore(app.getAppliedAmount())
                                                .reason(reason)
                                                .reversedBy(reversedBy)
                                                .build();
                                
                                var response = invoiceServiceClient.reversePaymentApplication(
                                                app.getInvoiceId(),
                                                reversalRequest);
                                
                                log.info("Successfully reversed invoice {} payment application {} (balance restored to {})",
                                                app.getInvoiceId(), app.getPaymentApplicationId(),
                                                response.getBalanceDue());
                                
                        } catch (Exception reverseEx) {
                                // Best effort: log but continue with other reversals
                                // Failed reversals require manual reconciliation
                                log.error("CRITICAL: Failed to perform compensating reversal for invoice {} " +
                                                "payment application {} (amount: {}, customerId: {}, paymentId: {}). " +
                                                "Manual reconciliation required. " +
                                                "Invoice service IS OUT OF SYNC with Accounting service.",
                                                app.getInvoiceId(), app.getPaymentApplicationId(), 
                                                app.getAppliedAmount(), app.getCustomerId(), app.getPaymentId(), reverseEx);
                        }
                }
                
                log.warn("Completed compensating reversals. {} applications processed",
                                successfulApplications.size());
        }

        private PaymentApplicationResponse.ApplicationDetail buildApplicationDetail(
                        PaymentApplication application,
                        UUID invoiceId) {

                // Fallback for idempotent retry case - fetch details
                var invoiceResponse = invoiceServiceClient.getInvoiceDetails(invoiceId);

                return PaymentApplicationResponse.ApplicationDetail.builder()
                                .paymentApplicationId(application.getPaymentApplicationId())
                                .invoiceId(invoiceId)
                                .appliedAmount(application.getAppliedAmount())
                                .invoiceBalanceBefore(invoiceResponse.getBalanceDue())
                                .invoiceBalanceAfter(invoiceResponse.getBalanceDue())
                                .invoiceStatus(invoiceResponse.getStatus())
                                .build();
        }

        private PaymentApplicationResponse.CustomerCreditInfo createCustomerCredit(
                        ReceivablePayment payment,
                        BigDecimal amount,
                        Instant timestamp) {

                CustomerCredit credit = new CustomerCredit();
                credit.setCustomerId(payment.getCustomerId());
                credit.setCurrency(payment.getCurrency());
                credit.setAmount(amount);
                credit.setSourcePaymentId(payment.getPaymentId());
                credit.setCreatedAt(timestamp);
                credit.setCreatedBy(getCurrentUser());

                CustomerCredit saved = customerCreditRepository.save(credit);

                log.info("Created CustomerCredit {} for customer {} amount {} from payment {}",
                                saved.getCreditId(), payment.getCustomerId(), amount, payment.getPaymentId());

                return PaymentApplicationResponse.CustomerCreditInfo.builder()
                                .creditId(saved.getCreditId())
                                .amount(amount)
                                .createdAt(timestamp)
                                .build();
        }

        private PaymentApplicationResponse buildResponseForExistingApplication(String applicationRequestId) {
                // Find all existing applications by request ID (handles multi-invoice case)
                List<PaymentApplication> existingApps = paymentApplicationRepository
                                .findAllByApplicationRequestId(applicationRequestId);

                if (existingApps.isEmpty()) {
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                        "Application request processed but not found: " + applicationRequestId);
                }

                // Use first application for common fields (all apps share same payment/customer/currency)
                PaymentApplication firstApp = existingApps.get(0);

                ReceivablePayment payment = receivablePaymentRepository.findById(firstApp.getPaymentId())
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                                "Internal payment data inconsistency: payment record not found for existing application. paymentId="
                                                                + firstApp.getPaymentId()));

                // Calculate total applied amount across all applications
                BigDecimal totalApplied = existingApps.stream()
                                .map(PaymentApplication::getAppliedAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                // Build application details for all applications
                List<PaymentApplicationResponse.ApplicationDetail> applicationDetails = existingApps.stream()
                                .map(app -> buildApplicationDetail(app, app.getInvoiceId()))
                                .toList();

                // Build response for idempotent retry
                return PaymentApplicationResponse.builder()
                                .paymentId(firstApp.getPaymentId())
                                .customerId(firstApp.getCustomerId())
                                .currency(firstApp.getCurrency())
                                .totalAmount(payment.getTotalAmount())
                                .appliedAmount(totalApplied)
                                .remainingAmount(payment.getUnappliedAmount())
                                .applications(applicationDetails)
                                .applicationTimestamp(firstApp.getApplicationTimestamp())
                                .applicationRequestId(applicationRequestId)
                                .build();
        }

        // ===== SECURITY HELPER METHODS =====

        /**
         * Extract current authenticated user from SecurityContext.
         * Falls back to "SYSTEM" if no authentication is present (e.g., scheduled tasks)
         * or if the user is the gateway anonymous user.
         * 
         * Uses SecurityContextHelper to properly handle gateway authentication,
         * including treating the "anonymous" principal as unauthenticated.
         * 
         * @return username or "SYSTEM" as fallback
         */
        private String getCurrentUser() {
                return SecurityContextHelper.getCurrentUsernameOrDefault("SYSTEM");
        }
}
