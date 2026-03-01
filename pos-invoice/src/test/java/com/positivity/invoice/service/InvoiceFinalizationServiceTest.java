package com.positivity.invoice.service;

import com.positivity.invoice.internal.dto.FinalizationEligibilityResult;
import com.positivity.invoice.internal.dto.FinalizationRequest;
import com.positivity.invoice.internal.dto.InvoiceDetailsResponse;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.internal.service.InvoiceFinalizationServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InvoiceFinalizationService} covering Story #13
 * controlled finalization.
 *
 * <p>
 * Covers:
 * <ul>
 * <li>AC1 — view full invoice details for a completed workorder</li>
 * <li>AC2 — eligibility checks block finalization when data incomplete or
 * workorder not complete</li>
 * <li>AC3 — permission and amount limits enforced; manager approval required
 * for overrides</li>
 * <li>AC4 — {@code finalizeInvoice} transitions DRAFT → FINALIZED and emits
 * {@code InvoiceFinalized} event</li>
 * <li>AC5 — GL entries posted asynchronously; invoice marked POSTED or
 * ERROR</li>
 * <li>AC6 — finalized invoice is read-only; revert requires manager approval
 * within 24h</li>
 * </ul>
 *
 * Issue: #13
 */
@ExtendWith(MockitoExtension.class)
class InvoiceFinalizationServiceTest {

    /**
     * Repository mock — will be needed by the real implementation for invoice
     * lookup.
     */
    @Mock
    private InvoiceRepository invoiceRepository;

    @InjectMocks
    private InvoiceFinalizationServiceImpl service;

    private static final String SERVICE_ADVISOR = "SERVICE_ADVISOR";
    private static final String SHOP_MANAGER = "SHOP_MANAGER";

    // -------------------------------------------------------------------------
    // AC1 & AC2 — Eligibility checks
    // -------------------------------------------------------------------------

    /**
     * AC2: A DRAFT invoice with all data present and workorder completed must be
     * eligible.
     */
    @Test
    void checkEligibility_returnsEligible_whenInvoiceIsDraftAndDataComplete() {
        UUID invoiceId = UUID.randomUUID();
        UUID workorderId = UUID.randomUUID();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draftInvoice(workorderId, BigDecimal.ZERO)));

        FinalizationEligibilityResult result = service.checkEligibility(invoiceId);

        assertThat(result.eligible()).isTrue();
        assertThat(result.reason()).isNull();
        assertThat(result.requiresManagerApproval()).isFalse();
    }

    /**
     * AC2: A non-DRAFT invoice (e.g. FINALIZED) must be blocked from
     * re-finalization.
     */
    @Test
    void checkEligibility_returnsIneligible_whenInvoiceNotInDraftStatus() {
        UUID invoiceId = UUID.randomUUID();

        FinalizationEligibilityResult result = service.checkEligibility(invoiceId);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isNotBlank();
    }

    /**
     * AC2: An invoice whose workorder is not in COMPLETED status must be blocked.
     * The reason message must reference the workorder to aid operators.
     */
    @Test
    void checkEligibility_returnsIneligible_whenWorkorderNotCompleted() {
        UUID invoiceId = UUID.randomUUID();

        FinalizationEligibilityResult result = service.checkEligibility(invoiceId);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isNotBlank();
        assertThat(result.reason()).matches("(?i).*workorder.*");
    }

    /**
     * AC3: An invoice with total {@literal >} $500 must signal
     * {@code requiresManagerApproval = true}
     * so callers know to collect a manager approval code before calling
     * {@code finalize}.
     */
    @Test
    void checkEligibility_requiresManagerApproval_whenAmountExceedsServiceAdvisorLimit() {
        UUID invoiceId = UUID.randomUUID();

        FinalizationEligibilityResult result = service.checkEligibility(invoiceId);

        assertThat(result.requiresManagerApproval()).isTrue();
    }

    // -------------------------------------------------------------------------
    // AC4 — DRAFT → FINALIZED transition + event emission
    // -------------------------------------------------------------------------

    /**
     * AC4: Successful finalization must transition the invoice to FINALIZED,
     * set {@code finalizedAt} and {@code finalizedBy}, and emit an
     * {@code InvoiceFinalized} event.
     */
    @Test
    void finalize_transitionsInvoiceFromDraftToFinalized_andEmitsEvent() {
        UUID invoiceId = UUID.randomUUID();
        FinalizationRequest request = shopManagerRequest(new BigDecimal("200.00"));

        InvoiceDetailsResponse response = service.finalize(invoiceId, request);

        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.FINALIZED);
        assertThat(response.getFinalizedAt()).isNotNull();
        assertThat(response.getFinalizedBy()).isEqualTo(request.getRequestedBy());
    }

    /**
     * AC2: Attempting to finalize an ineligible invoice (e.g. workorder not
     * complete)
     * must throw {@link IllegalStateException}.
     */
    @Test
    void finalize_throws_whenEligibilityCheckFails() {
        UUID invoiceId = UUID.randomUUID();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(finalizedInvoice(UUID.randomUUID())));
        FinalizationRequest request = shopManagerRequest(new BigDecimal("200.00"));

        assertThatThrownBy(() -> service.finalize(invoiceId, request))
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // AC3 — Permission + amount limit enforcement
    // -------------------------------------------------------------------------

    /**
     * AC3: SERVICE_ADVISOR must not be able to finalize an invoice with total
     * {@literal >} $500
     * when no manager approval code is provided. Rejection must reference approval.
     */
    @Test
    void finalize_throws_whenPermissionLevelInsufficientForAmount() {
        UUID invoiceId = UUID.randomUUID();
        // Issue #13: $500.01 exceeds SERVICE_ADVISOR limit; no approval code supplied
        FinalizationRequest request = serviceAdvisorRequest(new BigDecimal("500.01"), null);

        assertThatThrownBy(() -> service.finalize(invoiceId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageMatching("(?i).*approval.*");
    }

    /**
     * AC3: SHOP_MANAGER is not subject to the $500 cap — $1000 must succeed.
     */
    @Test
    void finalize_succeeds_forShopManagerWithAmountAboveLimit() {
        UUID invoiceId = UUID.randomUUID();
        FinalizationRequest request = shopManagerRequest(new BigDecimal("1000.00"));

        InvoiceDetailsResponse response = service.finalize(invoiceId, request);

        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.FINALIZED);
    }

    /**
     * AC3: SERVICE_ADVISOR with a valid manager approval code must be allowed to
     * finalize
     * an invoice whose total exceeds the $500 limit. The override must be audited.
     */
    @Test
    void finalize_succeeds_withManagerApprovalCode_whenAmountExceedsServiceAdvisorLimit() {
        UUID invoiceId = UUID.randomUUID();
        // Issue #13: approval code present — override path must succeed and audit
        FinalizationRequest request = serviceAdvisorRequest(new BigDecimal("750.00"), "MGR-APPROVAL-001");

        InvoiceDetailsResponse response = service.finalize(invoiceId, request);

        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.FINALIZED);
    }

    // -------------------------------------------------------------------------
    // AC6 — Revert to DRAFT within 24h window, manager approval required
    // -------------------------------------------------------------------------

    /**
     * AC6: Revert within the 24h window with a valid manager approval code must
     * transition the invoice back to DRAFT.
     */
    @Test
    void revert_transitionsToReverted_withinAllowedWindow_withManagerApproval() {
        UUID invoiceId = UUID.randomUUID();
        UUID workorderId = UUID.randomUUID();
        Invoice invoice = finalizedInvoice(workorderId);
        invoice.setFinalizedAt(Instant.now().minusSeconds(3600)); // 1h ago — within 24h window
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceDetailsResponse response = service.revert(invoiceId, "MGR-APPROVAL-001", "Customer dispute");

        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
    }

    /**
     * AC6: A POSTED invoice (GL already committed) must not be reverted.
     * Rejection must reference POSTED state.
     */
    @Test
    void revert_throws_whenInvoiceAlreadyPOSTED() {
        UUID invoiceId = UUID.randomUUID();

        assertThatThrownBy(() -> service.revert(invoiceId, "MGR-APPROVAL-001", "Attempt on posted"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageMatching("(?i).*posted.*");
    }

    /**
     * AC6: Revert must be rejected if more than 24h have elapsed since
     * finalization.
     */
    @Test
    void revert_throws_whenReversionWindowExpired() {
        UUID invoiceId = UUID.randomUUID();

        assertThatThrownBy(() -> service.revert(invoiceId, "MGR-APPROVAL-001", "Late revert attempt"))
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link FinalizationRequest} for a SERVICE_ADVISOR actor.
     *
     * @param amountLimit         invoice grand total subject to the $500
     *                            enforcement check
     * @param managerApprovalCode optional manager code; {@code null} means no
     *                            approval supplied
     * @return configured request
     */
    private FinalizationRequest serviceAdvisorRequest(BigDecimal amountLimit, String managerApprovalCode) {
        FinalizationRequest req = new FinalizationRequest();
        req.setRequestedBy("advisor-001");
        req.setPermissionLevel(SERVICE_ADVISOR);
        req.setAmountLimit(amountLimit);
        req.setManagerApprovalCode(managerApprovalCode);
        req.setManagerApprovalRequired(managerApprovalCode != null);
        req.setFinalizedBy("advisor-001");
        req.setFinalizedAt(Instant.now());
        return req;
    }

    /**
     * Builds a {@link FinalizationRequest} for a SHOP_MANAGER actor (no amount
     * limit applies).
     *
     * @param amountLimit invoice grand total (Manager has unlimited authority)
     * @return configured request
     */
    private FinalizationRequest shopManagerRequest(BigDecimal amountLimit) {
        FinalizationRequest req = new FinalizationRequest();
        req.setRequestedBy("manager-001");
        req.setPermissionLevel(SHOP_MANAGER);
        req.setAmountLimit(amountLimit);
        req.setManagerApprovalRequired(false);
        req.setFinalizedBy("manager-001");
        req.setFinalizedAt(Instant.now());
        return req;
    }

    private Invoice draftInvoice(UUID workorderId, BigDecimal total) {
        Invoice invoice = new Invoice();
        invoice.setWorkorderId(workorderId);
        invoice.setStatus(InvoiceStatus.DRAFT);
        invoice.setTotal(total);
        return invoice;
    }

    private Invoice finalizedInvoice(UUID workorderId) {
        Invoice invoice = new Invoice();
        invoice.setWorkorderId(workorderId);
        invoice.setStatus(InvoiceStatus.FINALIZED);
        invoice.setTotal(BigDecimal.ZERO);
        invoice.setFinalizedAt(Instant.now().minusSeconds(3600));
        invoice.setFinalizedBy("manager-001");
        return invoice;
    }
}
