package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.dto.PaymentApplicationResponse;
import com.positivity.accounting.internal.entity.CustomerCredit;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.PaymentApplication;
import com.positivity.accounting.internal.entity.PaymentApplicationReversal;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import com.positivity.accounting.internal.enums.InvoiceStatus;
import com.positivity.accounting.internal.repository.CustomerCreditRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationReversalRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
public class PaymentApplicationServiceImpl implements com.positivity.accounting.service.PaymentApplicationService {
    private static final String PAYMENT_NOT_FOUND = "Payment not found: ";

    private final Clock clock;
    private final ReceivablePaymentRepository receivablePaymentRepository;
    private final PaymentApplicationRepository paymentApplicationRepository;
    private final CustomerCreditRepository customerCreditRepository;
    private final PaymentApplicationReversalRepository reversalRepository;
    private final InvoiceBalanceCalculator invoiceBalanceCalculator;

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
            log.info("PaymentCleared event {} already processed, returning existing payment", sourceEventId);
            return receivablePaymentRepository
                    .findBySourceEventId(sourceEventId)
                    .orElseThrow(() -> new IllegalStateException("Payment not found after existence check"));
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
        log.info(
                "Created ReceivablePayment {} for customer {} with amount {} (event: {})",
                paymentId,
                customerId,
                totalAmount,
                sourceEventId);
        return saved;
    }

    /**
     * Apply a payment to one or more invoices atomically.
     *
     * <p>
     * <strong>Atomicity Guarantee:</strong> This operation ensures atomicity across
     * all invoice
     * applications through compensating reversals. If any invoice service call
     * fails, all previously
     * successful invoice mutations are reversed before the local DB transaction
     * rolls back. This
     * prevents the Accounting and Invoice services from becoming out of sync.
     *
     * <p>
     * <strong>Idempotency Requirement:</strong> The Invoice service MUST implement
     * idempotent
     * payment application using {@code paymentApplicationId} as the idempotency
     * key. This allows
     * safe retries and prevents duplicate applications if the same
     * {@code paymentApplicationId}
     * is sent multiple times.
     *
     * <p>
     * <strong>Failure Handling:</strong>
     * <ul>
     * <li>If validation fails, throws exception before any mutations occur</li>
     * <li>If an invoice service call fails, performs compensating reversals on all
     * successfully
     * applied invoices, then rethrows the exception to trigger DB transaction
     * rollback</li>
     * <li>All compensating reversals are logged for audit trail and debugging</li>
     * </ul>
     *
     * Main Success Scenario (from Issue #114):
     * 1. Validate payment is AVAILABLE with sufficient funds
     * 2. Validate each invoice is applicable (not PaidInFull/Voided/Cancelled)
     * 3. Validate requested amounts
     * 4. Create immutable PaymentApplication records
     * 5. Update invoice balances and statuses (with compensating reversal on
     * failure)
     * 6. Update payment unappliedAmount
     * 7. Handle overpayment (create CustomerCredit)
     * 8. Emit events for downstream consumers
     *
     * @param paymentId payment to apply
     * @param request   application request with invoices and amounts
     * @return application response with details
     * @throws ResponseStatusException with NOT_FOUND if payment not found
     * @throws ResponseStatusException with BAD_REQUEST if validation fails or
     *                                 insufficient funds
     * @throws ResponseStatusException with CONFLICT if currency mismatch or invoice
     *                                 not applicable
     * @throws ResponseStatusException with SERVICE_UNAVAILABLE if invoice service
     *                                 call fails (after compensating reversals)
     */
    public PaymentApplicationResponse applyPaymentToInvoices(
            @NonNull UUID paymentId, @NonNull PaymentApplicationRequest request) {

        // Idempotency check
        if (paymentApplicationRepository.existsByApplicationRequestId(request.getApplicationRequestId())) {
            log.info(
                    "Application request {} already processed, returning existing result",
                    request.getApplicationRequestId());
            return buildResponseForExistingApplication(request.getApplicationRequestId());
        }

        ReceivablePayment payment = getAvailablePayment(paymentId);
        BigDecimal totalApplicationAmount = calculateTotalApplicationAmount(request);
        validateSufficientFunds(payment, totalApplicationAmount);
        InvoiceApplicationValidation validation = validateAndCapApplications(request);

        // Capture current user early to avoid issues in exception handler
        String currentUser = getCurrentUser();
        Instant applicationTimestamp = Instant.now(clock);

        List<PaymentApplicationResponse.ApplicationDetail> applicationDetails = createApplicationsAndUpdateInvoices(
                paymentId, request, payment, validation.cappedAmounts(), currentUser, applicationTimestamp);

        // 6. Capture unapplied amount before applying, for overpayment credit
        // calculation
        BigDecimal unappliedBeforeApplication = payment.getUnappliedAmount();

        // 7. Update payment unappliedAmount (apply actual applied amount)
        payment.applyAmount(validation.actualTotalApplicationAmount());
        payment.setUpdatedAt(applicationTimestamp);
        payment.setModifiedBy(currentUser);

        // 8. Handle overpayment - create CustomerCredit if there's overpayment
        PaymentApplicationResponse.CustomerCreditInfo creditInfo = null;

        if (validation.overpaymentAmount().compareTo(BigDecimal.ZERO) > 0) {
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

            log.info(
                    "Overpayment detected: requested={}, applied={}, overpayment={}, "
                            + "unapplied before={}, unapplied after={}, credit={}",
                    totalApplicationAmount,
                    validation.actualTotalApplicationAmount(),
                    validation.overpaymentAmount(),
                    unappliedBeforeApplication,
                    unappliedAfterApplication,
                    unappliedAfterApplication);
        }

        receivablePaymentRepository.save(payment);

        // 9. Build response
        return PaymentApplicationResponse.builder()
                .paymentId(paymentId)
                .customerId(payment.getCustomerId())
                .currency(payment.getCurrency())
                .totalAmount(payment.getTotalAmount())
                .appliedAmount(validation.actualTotalApplicationAmount())
                .remainingAmount(payment.getUnappliedAmount())
                .applications(applicationDetails)
                .customerCredit(creditInfo)
                .applicationTimestamp(applicationTimestamp)
                .applicationRequestId(request.getApplicationRequestId())
                .build();
    }

    /**
     * Void a receivable payment so it can no longer be applied.
     *
     * <p>
     * Guardrails:
     * <ul>
     * <li>Payment must exist</li>
     * <li>Payment cannot be voided if any invoice applications already exist</li>
     * <li>Operation is idempotent for already-voided payments</li>
     * </ul>
     *
     * @param paymentId receivable payment ID
     */
    public void voidPayment(@NonNull UUID paymentId) {
        ReceivablePayment payment = receivablePaymentRepository
                .findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, PAYMENT_NOT_FOUND + paymentId));

        List<PaymentApplication> existingApplications = paymentApplicationRepository.findByPayment_PaymentId(paymentId);
        if (!existingApplications.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Payment " + paymentId
                            + " cannot be voided because invoice applications already exist. "
                            + "Reverse payment applications first.");
        }

        if (payment.getStatus() == ReceivablePaymentStatus.FULLY_APPLIED
                && payment.getUnappliedAmount() != null
                && payment.getUnappliedAmount().compareTo(BigDecimal.ZERO) == 0) {
            log.info("Payment {} already voided/inactive; no action taken", paymentId);
            return;
        }

        payment.setUnappliedAmount(BigDecimal.ZERO);
        payment.setStatus(ReceivablePaymentStatus.FULLY_APPLIED);
        payment.setUpdatedAt(Instant.now(clock));
        payment.setModifiedBy(getCurrentUser());

        receivablePaymentRepository.save(payment);
        log.info("Voided receivable payment {}", paymentId);
    }

    /**
     * Reverse all payment applications for a payment.
     *
     * <p>
     * Reuses single-application reversal logic so invoice restoration and audit
     * behavior stay consistent.
     *
     * @param paymentId receivable payment ID
     * @param reason    reversal reason
     */
    public void reversePayment(@NonNull UUID paymentId, @NonNull String reason) {
        receivablePaymentRepository
                .findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, PAYMENT_NOT_FOUND + paymentId));

        List<PaymentApplication> applications = paymentApplicationRepository.findByPayment_PaymentId(paymentId);
        if (applications.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Payment " + paymentId + " has no applications to reverse");
        }

        int reversedCount = 0;
        for (PaymentApplication application : applications) {
            UUID applicationId = application.getPaymentApplicationId();
            if (reversalRepository.existsByOriginalPaymentApplication_PaymentApplicationId(applicationId)) {
                continue;
            }

            reversePaymentApplication(applicationId, reason);
            reversedCount++;
        }

        if (reversedCount == 0) {
            log.info("All payment applications for payment {} were already reversed", paymentId);
            return;
        }

        log.info("Reversed {} payment applications for payment {}", reversedCount, paymentId);
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
            @NonNull UUID paymentApplicationId, @NonNull String reason) {

        String reversedBy = getCurrentUser();

        // 1. Check if already reversed
        if (reversalRepository.existsByOriginalPaymentApplication_PaymentApplicationId(paymentApplicationId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Payment application " + paymentApplicationId + " has already been reversed");
        }

        // 2. Get original application
        PaymentApplication original = paymentApplicationRepository
                .findById(paymentApplicationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Payment application not found: " + paymentApplicationId));

        // 3. Create reversal record
        PaymentApplicationReversal reversal = new PaymentApplicationReversal();
        reversal.setOriginalPaymentApplicationId(paymentApplicationId);
        reversal.setAmount(original.getAppliedAmount());
        reversal.setReason(reason);
        reversal.setReversedBy(reversedBy);
        reversal.setReversedAt(Instant.now(clock));

        PaymentApplicationReversal saved = reversalRepository.save(reversal);

        // 4. Restore payment unappliedAmount
        ReceivablePayment payment = receivablePaymentRepository
                .findById(original.getPaymentId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Internal error while reversing payment application "
                                + paymentApplicationId
                                + ": associated payment " + original.getPaymentId()
                                + " not found"));

        payment.reverseAmount(original.getAppliedAmount());
        payment.setUpdatedAt(Instant.now(clock));
        payment.setModifiedBy(reversedBy);
        receivablePaymentRepository.save(payment);

        // The invoice balance is derived from accounting's own records (ADR-0044 R6), so the
        // reversal row above restores it — nothing to tell pos-invoice.
        log.info(
                "Reversed invoice balance for invoice {}, restored amount: {} (reason: {})",
                original.getInvoiceId(),
                original.getAppliedAmount(),
                reason);

        log.info(
                "Created reversal {} for application {} by {} (reason: {})",
                saved.getReversalId(),
                paymentApplicationId,
                reversedBy,
                reason);

        return saved;
    }

    /**
     * Get payment application by ID.
     *
     * @throws ResponseStatusException with NOT_FOUND if application not found
     */
    @Transactional(readOnly = true)
    public PaymentApplication getPaymentApplication(@NonNull UUID paymentApplicationId) {
        return paymentApplicationRepository
                .findById(paymentApplicationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Payment application not found: " + paymentApplicationId));
    }

    /**
     * List all applications for a payment.
     */
    @Transactional(readOnly = true)
    public List<PaymentApplication> listApplicationsForPayment(@NonNull UUID paymentId) {
        return paymentApplicationRepository.findByPayment_PaymentId(paymentId);
    }

    /**
     * List all applications for an invoice.
     */
    @Transactional(readOnly = true)
    public List<PaymentApplication> listApplicationsForInvoice(@NonNull UUID invoiceId) {
        return paymentApplicationRepository.findByInvoiceId(invoiceId);
    }

    // ===== PRIVATE HELPER METHODS =====

    /**
     * Validate an invoice application against the {@code ext_invoice} replica and accounting's
     * derived balance (ADR-0044, #842). The invoice must exist in the replica, be in an
     * AR-eligible lifecycle state (FINALIZED/POSTED), and still carry a positive balance. No
     * currency check: the replica carries none (pos-invoice is single-currency); payments and
     * credits are recorded in the payment's currency.
     */
    private InvoiceSnapshot validateInvoiceApplication(PaymentApplicationRequest.InvoiceApplication invoiceApp) {

        // Validate amount > 0
        if (invoiceApp.getAmountToApply().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Amount to apply must be greater than 0 for invoice " + invoiceApp.getInvoiceId());
        }

        ExtInvoice invoice = invoiceBalanceCalculator
                .findInvoice(invoiceApp.getInvoiceId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Invoice " + invoiceApp.getInvoiceId() + " not found in the invoice replica"));

        if (!invoiceBalanceCalculator.isArEligible(invoice)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Invoice " + invoiceApp.getInvoiceId() + " is not applicable for payment (status: "
                            + invoice.getStatus() + ")");
        }

        BigDecimal balanceDue = invoiceBalanceCalculator.balanceDue(invoice);
        if (balanceDue.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Invoice " + invoiceApp.getInvoiceId() + " is not applicable for payment (status: "
                            + InvoiceStatus.PAID_IN_FULL + ")");
        }

        log.info(
                "Invoice validation passed for invoice {} (status: {}, balanceDue: {})",
                invoiceApp.getInvoiceId(),
                invoice.getStatus(),
                balanceDue);

        return new InvoiceSnapshot(invoice, balanceDue);
    }

    private ReceivablePayment getAvailablePayment(UUID paymentId) {
        ReceivablePayment payment = receivablePaymentRepository
                .findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, PAYMENT_NOT_FOUND + paymentId));
        if (payment.getStatus() != ReceivablePaymentStatus.AVAILABLE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Payment " + paymentId + " is not available (status: " + payment.getStatus() + ")");
        }
        return payment;
    }

    private BigDecimal calculateTotalApplicationAmount(PaymentApplicationRequest request) {
        return request.getApplications().stream()
                .map(PaymentApplicationRequest.InvoiceApplication::getAmountToApply)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void validateSufficientFunds(ReceivablePayment payment, BigDecimal totalApplicationAmount) {
        if (!payment.hasSufficientFunds(totalApplicationAmount)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    String.format(
                            "Insufficient funds: requested %s, available %s",
                            totalApplicationAmount, payment.getUnappliedAmount()));
        }
    }

    private InvoiceApplicationValidation validateAndCapApplications(PaymentApplicationRequest request) {
        BigDecimal actualTotalApplicationAmount = BigDecimal.ZERO;
        BigDecimal overpaymentAmount = BigDecimal.ZERO;
        Map<UUID, BigDecimal> cappedAmounts = new HashMap<>();

        for (PaymentApplicationRequest.InvoiceApplication invoiceApp : request.getApplications()) {
            InvoiceSnapshot snapshot = validateInvoiceApplication(invoiceApp);
            BigDecimal requestedAmount = invoiceApp.getAmountToApply();
            BigDecimal cappedAmount = requestedAmount.min(snapshot.balanceDue());
            BigDecimal excessForThisInvoice = requestedAmount.subtract(cappedAmount);

            cappedAmounts.put(invoiceApp.getInvoiceId(), cappedAmount);
            actualTotalApplicationAmount = actualTotalApplicationAmount.add(cappedAmount);
            overpaymentAmount = overpaymentAmount.add(excessForThisInvoice);

            if (excessForThisInvoice.compareTo(BigDecimal.ZERO) > 0) {
                log.info(
                        "Capped application to invoice {} from {} to {} (excess: {})",
                        invoiceApp.getInvoiceId(),
                        requestedAmount,
                        cappedAmount,
                        excessForThisInvoice);
            }
        }

        return new InvoiceApplicationValidation(actualTotalApplicationAmount, overpaymentAmount, cappedAmounts);
    }

    private List<PaymentApplicationResponse.ApplicationDetail> createApplicationsAndUpdateInvoices(
            UUID paymentId,
            PaymentApplicationRequest request,
            ReceivablePayment payment,
            Map<UUID, BigDecimal> cappedAmounts,
            String currentUser,
            Instant applicationTimestamp) {
        List<PaymentApplicationResponse.ApplicationDetail> applicationDetails = new ArrayList<>();
        List<PaymentApplication> successfulApplications = new ArrayList<>();

        // All mutations are accounting-local (ADR-0044, #842): a failure rolls back the whole
        // transaction, so no cross-service compensating reversals are needed anymore.
        for (PaymentApplicationRequest.InvoiceApplication invoiceApp : request.getApplications()) {
            applySingleInvoiceApplication(
                    new InvoiceApplicationExecutionContext(
                            paymentId,
                            request.getApplicationRequestId(),
                            payment,
                            currentUser,
                            applicationTimestamp,
                            successfulApplications,
                            applicationDetails),
                    new SingleInvoiceApplicationInput(
                            invoiceApp.getInvoiceId(), cappedAmounts.get(invoiceApp.getInvoiceId())));
        }
        return applicationDetails;
    }

    private void applySingleInvoiceApplication(
            InvoiceApplicationExecutionContext context, SingleInvoiceApplicationInput input) {
        BigDecimal amountToApply = input.amountToApply();
        UUID invoiceId = input.invoiceId();

        if (amountToApply.compareTo(BigDecimal.ZERO) == 0) {
            log.info("Skipping invoice {} - capped amount is 0 (already paid in full)", invoiceId);
            return;
        }

        UUID applicationId = UUIDv7Generator.generate();

        // Balance derived from accounting's own records (ADR-0044 R6); earlier applications in
        // this request are flushed before the sum query, so repeat invoices accumulate correctly.
        ExtInvoice invoice = invoiceBalanceCalculator
                .findInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Invoice " + invoiceId + " not found in the invoice replica"));
        BigDecimal balanceBefore = invoiceBalanceCalculator.balanceDue(invoice);
        BigDecimal balanceAfter = balanceBefore.subtract(amountToApply);
        InvoiceStatus statusAfter = invoiceBalanceCalculator.deriveArStatus(invoice, balanceAfter);

        log.info(
                "Applied payment {} to invoice {}, new balance: {} (was {})",
                applicationId,
                invoiceId,
                balanceAfter,
                balanceBefore);

        PaymentApplication saved =
                paymentApplicationRepository.save(buildPaymentApplicationEntity(new PaymentApplicationEntityInput(
                        applicationId,
                        context.paymentId(),
                        context.applicationRequestId(),
                        context.payment(),
                        amountToApply,
                        invoiceId,
                        context.applicationTimestamp(),
                        context.currentUser(),
                        balanceBefore,
                        balanceAfter,
                        statusAfter)));

        context.successfulApplications().add(saved);
        context.applicationDetails().add(buildApplicationDetail(saved, invoiceId));

        log.info(
                "Created PaymentApplication {} for payment {} to invoice {} amount {}",
                saved.getPaymentApplicationId(),
                context.paymentId(),
                invoiceId,
                amountToApply);
    }

    private PaymentApplication buildPaymentApplicationEntity(PaymentApplicationEntityInput input) {
        PaymentApplication application = new PaymentApplication();
        application.setPaymentApplicationId(input.applicationId());
        application.setPayment(input.payment());
        application.setInvoiceId(input.invoiceId());
        application.setCustomerId(input.payment().getCustomerId());
        application.setCurrency(input.payment().getCurrency());
        application.setAppliedAmount(input.amountToApply());
        application.setInvoiceBalanceBefore(input.balanceBefore());
        application.setInvoiceBalanceAfter(input.balanceAfter());
        application.setInvoiceStatus(input.statusAfter());
        application.setApplicationTimestamp(input.applicationTimestamp());
        application.setApplicationRequestId(input.applicationRequestId());
        application.setCreatedAt(input.applicationTimestamp());
        application.setCreatedBy(input.currentUser());
        return application;
    }

    public record InvoiceApplicationValidation(
            BigDecimal actualTotalApplicationAmount,
            BigDecimal overpaymentAmount,
            Map<UUID, BigDecimal> cappedAmounts) {}

    public record InvoiceApplicationExecutionContext(
            UUID paymentId,
            String applicationRequestId,
            ReceivablePayment payment,
            String currentUser,
            Instant applicationTimestamp,
            List<PaymentApplication> successfulApplications,
            List<PaymentApplicationResponse.ApplicationDetail> applicationDetails) {}

    public record SingleInvoiceApplicationInput(UUID invoiceId, BigDecimal amountToApply) {}

    public record PaymentApplicationEntityInput(
            UUID applicationId,
            UUID paymentId,
            String applicationRequestId,
            ReceivablePayment payment,
            BigDecimal amountToApply,
            UUID invoiceId,
            Instant applicationTimestamp,
            String currentUser,
            BigDecimal balanceBefore,
            BigDecimal balanceAfter,
            InvoiceStatus statusAfter) {}

    public record InvoiceSnapshot(ExtInvoice invoice, BigDecimal balanceDue) {}

    private PaymentApplicationResponse.ApplicationDetail buildApplicationDetail(
            PaymentApplication application, UUID invoiceId) {

        // Use persisted invoice data for idempotent retries (avoids N external service
        // calls)
        return PaymentApplicationResponse.ApplicationDetail.builder()
                .paymentApplicationId(application.getPaymentApplicationId())
                .invoiceId(invoiceId)
                .appliedAmount(application.getAppliedAmount())
                .invoiceBalanceBefore(application.getInvoiceBalanceBefore())
                .invoiceBalanceAfter(application.getInvoiceBalanceAfter())
                .invoiceStatus(application.getInvoiceStatus())
                .build();
    }

    private PaymentApplicationResponse.CustomerCreditInfo createCustomerCredit(
            ReceivablePayment payment, BigDecimal amount, Instant timestamp) {

        CustomerCredit credit = new CustomerCredit();
        credit.setCustomerId(payment.getCustomerId());
        credit.setCurrency(payment.getCurrency());
        credit.setAmount(amount);
        credit.setSourcePaymentId(payment.getPaymentId());
        credit.setCreatedAt(timestamp);
        credit.setCreatedBy(getCurrentUser());

        CustomerCredit saved = customerCreditRepository.save(credit);

        log.info(
                "Created CustomerCredit {} for customer {} amount {} from payment {}",
                saved.getCreditId(),
                payment.getCustomerId(),
                amount,
                payment.getPaymentId());

        return PaymentApplicationResponse.CustomerCreditInfo.builder()
                .creditId(saved.getCreditId())
                .amount(amount)
                .createdAt(timestamp)
                .build();
    }

    private PaymentApplicationResponse buildResponseForExistingApplication(String applicationRequestId) {
        // Find all existing applications by request ID (handles multi-invoice case)
        List<PaymentApplication> existingApps =
                new ArrayList<>(paymentApplicationRepository.findAllByApplicationRequestId(applicationRequestId));

        if (existingApps.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Application request processed but not found: " + applicationRequestId);
        }

        // Sort by timestamp then ID for deterministic ordering
        existingApps.sort((a, b) -> {
            int timestampCompare = a.getApplicationTimestamp().compareTo(b.getApplicationTimestamp());
            return timestampCompare != 0
                    ? timestampCompare
                    : a.getPaymentApplicationId().compareTo(b.getPaymentApplicationId());
        });

        // Use first application for common fields and validate consistency
        PaymentApplication firstApp = existingApps.get(0);

        // Validate all applications share same payment/customer/currency (data
        // integrity check)
        for (PaymentApplication app : existingApps) {
            if (!app.getPaymentId().equals(firstApp.getPaymentId())
                    || !app.getCustomerId().equals(firstApp.getCustomerId())
                    || !app.getCurrency().equals(firstApp.getCurrency())) {
                log.error(
                        "Inconsistent application data for requestId {}: app {} has different payment/customer/currency",
                        applicationRequestId,
                        app.getPaymentApplicationId());
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Inconsistent application data for request: " + applicationRequestId);
            }
        }

        ReceivablePayment payment = receivablePaymentRepository
                .findById(firstApp.getPaymentId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
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
     * Falls back to "SYSTEM" if no authentication is present (e.g., scheduled
     * tasks)
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
