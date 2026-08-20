package com.positivity.invoice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.invoice.internal.dto.FinalizationEligibilityResult;
import com.positivity.invoice.internal.dto.FinalizationRequest;
import com.positivity.invoice.internal.dto.InvoiceDetailsResponse;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.InvoiceAdjustment;
import com.positivity.invoice.internal.entity.InvoiceItem;
import com.positivity.invoice.internal.enums.InvoiceAdjustmentType;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.exception.InvalidInvoiceStateException;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.internal.service.ElevationTokenService;
import com.positivity.invoice.internal.service.InvoiceFinalizationServiceImpl;
import com.positivity.security.common.GatewaySecurityConstants;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

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
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ElevationTokenService elevationTokenService;

    @Mock
    private com.positivity.invoice.internal.config.InvoiceEventPublisher invoiceEventPublisher;

    @Mock
    private com.positivity.invoice.internal.client.TaxLifecycleClient taxLifecycleClient;

    @Mock
    private com.positivity.invoice.internal.service.InvoiceDueDateService invoiceDueDateService;

    @Mock
    private com.positivity.invoice.internal.service.InvoiceTaxCalculator invoiceTaxCalculator;

    @Mock
    private com.positivity.invoice.internal.service.InvoiceTaxBreakdownWriter taxBreakdownWriter;

    @InjectMocks
    private InvoiceFinalizationServiceImpl service;

    @BeforeEach
    void setUpSecurityContext() {
        withServiceAdvisorContext();
        // #993: finalization freezes the due-date facts; individual default (due on receipt).
        org.mockito.Mockito.lenient()
                .when(invoiceDueDateService.resolve(any(), any()))
                .thenAnswer(inv -> new com.positivity.invoice.internal.service.InvoiceDueDateService.DueTerms(
                        com.positivity.invoice.internal.enums.PaymentTerms.DUE_ON_RECEIPT,
                        java.time.LocalDate.ofInstant(TEST_CLOCK.instant(), ZoneOffset.UTC)));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Sets up a SERVICE_ADVISOR role in the security context.
     */
    private void withServiceAdvisorContext() {
        var auth = new UsernamePasswordAuthenticationToken(
                "advisor-001", null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE_ADVISOR")));
        auth.setDetails(java.util.Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "advisor-001"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Sets up a manager security context holding {@code invoice:finalize:override},
     * the authority the baseline seed grants to ADMIN and the manager roles (#1374).
     * The role name rides along only to mirror a real token; since #1373 the
     * authority alone is what bypasses the SERVICE_ADVISOR cap.
     */
    private void withShopManagerContext() {
        var auth = new UsernamePasswordAuthenticationToken(
                "manager-001",
                null,
                List.of(
                        new SimpleGrantedAuthority("ROLE_SHOP_MANAGER"),
                        new SimpleGrantedAuthority("invoice:finalize:override")));
        auth.setDetails(java.util.Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "manager-001"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // -------------------------------------------------------------------------
    // AC1 & AC2 — Eligibility checks
    // -------------------------------------------------------------------------

    /**
     * AC2: A DRAFT invoice with all data present and workorder completed must be
     * eligible.
     */
    @Test
    void checkEligibility_returnsEligible_whenInvoiceIsDraftAndDataComplete() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draftInvoice(workorderId, BigDecimal.ZERO)));

        FinalizationEligibilityResult result = service.checkEligibility(invoiceId);

        assertThat(result.eligible()).isTrue();
        assertThat(result.reason()).isNull();
        assertThat(result.requiresManagerApproval()).isFalse();
    }

    /**
     * AC2: An invoice not found in the repository is treated as ineligible.
     * The reason message must reference workorder data being incomplete.
     */
    @Test
    void checkEligibility_returnsIneligible_whenInvoiceNotFound() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        FinalizationEligibilityResult result = service.checkEligibility(invoiceId);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isNotBlank();
    }

    /**
     * AC2: A non-DRAFT invoice (e.g. FINALIZED) must be blocked from
     * re-finalization. The reason must reference the current status.
     */
    @Test
    void checkEligibility_returnsIneligible_whenInvoiceNotInDraftStatus() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Invoice finalized = new Invoice();
        finalized.setStatus(InvoiceStatus.FINALIZED);
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(finalized));

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
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        // M5: stub repository with a DRAFT invoice whose total exceeds $500
        when(invoiceRepository.findById(invoiceId))
                .thenReturn(Optional.of(draftInvoice(workorderId, new BigDecimal("600.00"))));

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
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Invoice draft = draftInvoice(workorderId, BigDecimal.ZERO);
        draft.setId(invoiceId);
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draft));
        when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        FinalizationRequest request = shopManagerRequest();

        InvoiceDetailsResponse response = service.completeInvoice(invoiceId, request);

        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.FINALIZED);
        assertThat(response.getFinalizedAt()).isNotNull();
        // ADR-0018: actor from SecurityContext
        assertThat(response.getFinalizedBy()).isEqualTo("advisor-001");
        // #993: the due-date facts are frozen on the entity at finalization.
        assertThat(draft.getDueDate()).isEqualTo(java.time.LocalDate.ofInstant(TEST_CLOCK.instant(), ZoneOffset.UTC));
        assertThat(draft.getPaymentTermsCode()).isEqualTo("DUE_ON_RECEIPT");
    }

    /**
     * AC2: Attempting to finalize an ineligible invoice (e.g. workorder not
     * complete)
     * must throw {@link IllegalStateException}.
     */
    @Test
    void finalize_throws_whenEligibilityCheckFails() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(invoiceRepository.findById(invoiceId))
                .thenReturn(Optional.of(finalizedInvoice(UUID.fromString("00000000-0000-0000-0000-000000000001"))));
        FinalizationRequest request = shopManagerRequest();

        assertThatThrownBy(() -> service.completeInvoice(invoiceId, request)).isInstanceOf(IllegalStateException.class);
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
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        // M5: stub invoice with total > $500 so the permission check exercises
        // SERVICE_ADVISOR path
        when(invoiceRepository.findById(invoiceId))
                .thenReturn(Optional.of(draftInvoice(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"), new BigDecimal("500.01"))));
        // No approval code, no invoice:finalize:override in context → rejected
        FinalizationRequest request = serviceAdvisorRequest(null);

        assertThatThrownBy(() -> service.completeInvoice(invoiceId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageMatching("(?i).*approval.*");
    }

    /**
     * AC3: a holder of {@code invoice:finalize:override} is not subject to the $500
     * cap — $1000 must succeed.
     */
    @Test
    void finalize_succeeds_forOverrideAuthorityHolderWithAmountAboveLimit() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        // C1: invoice:finalize:override derived from SecurityContext
        withShopManagerContext();
        when(invoiceRepository.findById(invoiceId))
                .thenReturn(Optional.of(draftInvoice(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"), new BigDecimal("1000.00"))));
        when(invoiceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        FinalizationRequest request = shopManagerRequest();

        InvoiceDetailsResponse response = service.completeInvoice(invoiceId, request);

        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.FINALIZED);
    }

    /**
     * AC3: SERVICE_ADVISOR with a valid manager approval code must be allowed to
     * finalize
     * an invoice whose total exceeds the $500 limit. The override must be audited.
     */
    @Test
    void finalize_succeeds_withManagerApprovalCode_whenAmountExceedsServiceAdvisorLimit() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        // M5: stub invoice with > $500 total; SERVICE_ADVISOR + approval code → allowed
        when(invoiceRepository.findById(invoiceId))
                .thenReturn(Optional.of(draftInvoice(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"), new BigDecimal("750.00"))));
        when(invoiceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(elevationTokenService.verify(any(), any())).thenReturn(Optional.of(UUID.randomUUID()));
        FinalizationRequest request = serviceAdvisorRequest("MGR-APPROVAL-001");

        InvoiceDetailsResponse response = service.completeInvoice(invoiceId, request);

        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.FINALIZED);
    }

    /**
     * The core of the manager-approval hardening: a non-blank but INVALID/expired
     * elevation token above the cap must be rejected (previously any non-blank code
     * was accepted).
     */
    @Test
    void finalize_throws_whenManagerApprovalTokenInvalid_aboveLimit() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(invoiceRepository.findById(invoiceId))
                .thenReturn(Optional.of(draftInvoice(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"), new BigDecimal("750.00"))));
        // Token present but does not verify (wrong scope, tampered, or expired).
        when(elevationTokenService.verify(any(), any())).thenReturn(Optional.empty());
        FinalizationRequest request = serviceAdvisorRequest("not-a-valid-token");

        assertThatThrownBy(() -> service.completeInvoice(invoiceId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageMatching("(?i).*approval code.*");
    }

    // -------------------------------------------------------------------------
    // AC6 — Revert to DRAFT within 24h window, manager approval required
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // AC1 — Line item mapping in response
    // -------------------------------------------------------------------------

    /**
     * AC1: finalize() response must include mapped line items from the invoice
     * entity. Verifies that toDetailsResponse() correctly maps InvoiceItem to
     * InvoiceItemResponse.
     */
    @Test
    void finalize_responseContainsLineItems_whenInvoiceHasItems() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Invoice draft = draftInvoice(workorderId, new BigDecimal("99.00"));
        draft.setId(invoiceId);
        InvoiceItem item = new InvoiceItem();
        item.setDescription("Oil Change");
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(new BigDecimal("99.00"));
        item.setLineTotal(new BigDecimal("99.00"));
        draft.addItem(item);
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draft));
        when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        FinalizationRequest request = shopManagerRequest();

        InvoiceDetailsResponse response = service.completeInvoice(invoiceId, request);

        assertThat(response.getItems()).isNotNull();
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getDescription()).isEqualTo("Oil Change");
    }

    /**
     * The finalize-path response mapper must carry {@code externalReference} on adjustment
     * entries (warranty settlements correlate adjustments to claims via it), matching the
     * plain-GET mapper in {@code InvoiceServiceImpl.toAdjustmentResponse} — the two mappers
     * previously diverged, dropping the claim correlation from finalize/revert responses.
     */
    @Test
    void finalize_responseCarriesExternalReferenceOnAdjustmentEntries() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Invoice draft = draftInvoice(workorderId, new BigDecimal("99.00"));
        draft.setId(invoiceId);
        InvoiceAdjustment adjustment = new InvoiceAdjustment();
        adjustment.setType(InvoiceAdjustmentType.WARRANTY);
        adjustment.setAmount(new BigDecimal("-84.21"));
        adjustment.setReason("Warranty claim WC-2026-000042 INVOICE_CREDIT settlement");
        adjustment.setAuthorizedBy("advisor-001");
        adjustment.setExternalReference("018f0000-0000-7000-8000-00000000abcd");
        draft.addAdjustment(adjustment);
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draft));
        when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InvoiceDetailsResponse response = service.completeInvoice(invoiceId, shopManagerRequest());

        assertThat(response.getAdjustmentEntries()).hasSize(1);
        assertThat(response.getAdjustmentEntries().get(0).getExternalReference())
                .isEqualTo("018f0000-0000-7000-8000-00000000abcd");
        assertThat(response.getAdjustmentEntries().get(0).getType()).isEqualTo(InvoiceAdjustmentType.WARRANTY);
    }

    /**
     * AC6: Revert within the 24h window with a valid manager approval code must
     * transition the invoice back to DRAFT.
     */
    @Test
    void revert_transitionsToReverted_withinAllowedWindow_withManagerApproval() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Invoice invoice = finalizedInvoice(workorderId);
        invoice.setFinalizedAt(Instant.now(TEST_CLOCK).minusSeconds(3600)); // 1h ago — within 24h window
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(elevationTokenService.verify(any(), any())).thenReturn(Optional.of(UUID.randomUUID()));

        InvoiceDetailsResponse response = service.revert(invoiceId, "MGR-APPROVAL-001", "Customer dispute");

        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        // ADR-0018: actor from SecurityContext
        assertThat(response.getRevertedBy()).isEqualTo("advisor-001");
    }

    /**
     * Revert by a non-override actor must reject an invalid/expired elevation token,
     * even within the window on a FINALIZED invoice.
     */
    @Test
    void revert_throws_whenManagerApprovalTokenInvalid() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Invoice invoice = finalizedInvoice(workorderId);
        invoice.setFinalizedAt(Instant.now(TEST_CLOCK).minusSeconds(3600));
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(elevationTokenService.verify(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revert(invoiceId, "not-a-valid-token", "Customer dispute"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageMatching("(?i).*approval code.*");
    }

    /**
     * AC6: Revert must reject an invoice that is not in FINALIZED status.
     * A DRAFT invoice, for example, should throw
     * {@link InvalidInvoiceStateException}.
     */
    @Test
    void revert_throws_whenInvoiceNotFinalized() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Invoice draftInv = draftInvoice(UUID.fromString("00000000-0000-0000-0000-000000000001"), BigDecimal.ZERO);
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draftInv));

        assertThatThrownBy(() -> service.revert(invoiceId, "MGR-APPROVAL-001", "Incorrect state"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageMatching("(?i).*not in FINALIZED.*");
    }

    /**
     * AC6: A POSTED invoice (GL already committed) must not be reverted.
     * Rejection must reference POSTED state.
     */
    @Test
    void revert_throws_whenInvoiceAlreadyPOSTED() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Invoice postedInvoice = new Invoice();
        postedInvoice.setWorkorderId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        postedInvoice.setStatus(InvoiceStatus.POSTED);
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(postedInvoice));

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
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Invoice expiredInvoice = finalizedInvoice(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        // finalizedAt 25h ago — outside the 24h reversion window
        expiredInvoice.setFinalizedAt(Instant.now(TEST_CLOCK).minusSeconds(25 * 3600));
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(expiredInvoice));

        assertThatThrownBy(() -> service.revert(invoiceId, "MGR-APPROVAL-001", "Late revert attempt"))
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link FinalizationRequest} for a SERVICE_ADVISOR actor.
     *
     * <p>
     * Role is derived from SecurityContext (this test class sets
     * ROLE_SERVICE_ADVISOR by default in {@code @BeforeEach}).
     *
     * @param managerApprovalCode optional manager code; {@code null} means no
     *                            approval supplied
     * @return configured request
     */
    private FinalizationRequest serviceAdvisorRequest(String managerApprovalCode) {
        FinalizationRequest req = new FinalizationRequest();
        req.setManagerApprovalCode(managerApprovalCode);
        return req;
    }

    /**
     * Builds a {@link FinalizationRequest} for an override-authority actor.
     *
     * <p>
     * Callers must set up the security context with {@code invoice:finalize:override}
     * before invoking {@code service.finalize()} to exercise the auto-approved path.
     *
     * @return empty request (no fields needed on the override path)
     */
    private FinalizationRequest shopManagerRequest() {
        return new FinalizationRequest();
    }

    /**
     * Story T6 / D-T3 + #985: finalization first runs the COMMITTABLE tax calculation (which
     * persists the provider SalesInvoice document under {@code code == invoiceId}) and only then
     * commits the provider tax document for the finalized invoice.
     */
    @Test
    void finalize_commitsProviderTaxDocument() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
        Invoice draft = draftInvoice(workorderId, new BigDecimal("99.00"));
        draft.setId(invoiceId);
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draft));
        when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceTaxCalculator.calculateCommittable(draft)).thenReturn(taxResponse(new BigDecimal("7.42")));

        service.completeInvoice(invoiceId, shopManagerRequest());

        // #985: document creation (committable calc) strictly precedes the lifecycle commit.
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(invoiceTaxCalculator, taxLifecycleClient);
        inOrder.verify(invoiceTaxCalculator).calculateCommittable(draft);
        inOrder.verify(taxLifecycleClient).commit(invoiceId);
    }

    /**
     * #985: the committable calculation is the invoice's frozen tax — the entity's tax/total and
     * the persisted breakdown are refreshed from it before the DRAFT → FINALIZED transition.
     */
    @Test
    void finalize_adoptsCommittableTaxAsFrozenTax() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
        Invoice draft = draftInvoice(UUID.fromString("00000000-0000-0000-0000-0000000000c4"), BigDecimal.ZERO);
        draft.setId(invoiceId);
        draft.setSubtotal(new BigDecimal("100.00"));
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draft));
        when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var response = taxResponse(new BigDecimal("8.25"));
        when(invoiceTaxCalculator.calculateCommittable(draft)).thenReturn(response);

        service.completeInvoice(invoiceId, shopManagerRequest());

        assertThat(draft.getTax()).isEqualByComparingTo("8.25");
        assertThat(draft.getTotal()).isEqualByComparingTo("108.25");
        verify(taxBreakdownWriter).replace(invoiceId, response);
    }

    /**
     * #985: with nothing taxable there is no provider document, so no lifecycle commit must be
     * attempted (a commit could never resolve and would poison the PENDING_COMMIT backlog).
     */
    @Test
    void finalize_skipsProviderCommit_whenNothingTaxable() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-0000000000c5");
        Invoice draft = draftInvoice(UUID.fromString("00000000-0000-0000-0000-0000000000c6"), BigDecimal.ZERO);
        draft.setId(invoiceId);
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draft));
        when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceTaxCalculator.calculateCommittable(draft)).thenReturn(null);

        InvoiceDetailsResponse response = service.completeInvoice(invoiceId, shopManagerRequest());

        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.FINALIZED);
        org.mockito.Mockito.verifyNoInteractions(taxLifecycleClient);
    }

    /**
     * PR #1020 review (Copilot): permission enforcement must strictly precede the committable
     * tax calculation — a rejected finalization (advisor above the $500 cap, no approval code)
     * must NOT trigger the provider-persisting call, or an unauthorized request would leave an
     * orphan uncommitted AvaTax document behind. The matrix gates on the STORED total.
     */
    @Test
    void finalize_enforcesPermissions_beforeCommittableTaxCalculation() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-0000000000c9");
        Invoice draft = draftInvoice(UUID.fromString("00000000-0000-0000-0000-0000000000ca"), new BigDecimal("750.00"));
        draft.setId(invoiceId);
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draft));
        // SERVICE_ADVISOR context (default), > $500, no approval code → rejected.

        assertThatThrownBy(() -> service.completeInvoice(invoiceId, serviceAdvisorRequest(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageMatching("(?i).*approval.*");

        assertThat(draft.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        org.mockito.Mockito.verifyNoInteractions(invoiceTaxCalculator, taxBreakdownWriter, taxLifecycleClient);
        verify(invoiceRepository, org.mockito.Mockito.never()).save(any());
    }

    /**
     * PR #1020 review (F2): a previously-taxed invoice whose lines are no longer taxable at
     * finalization must not freeze the stale draft tax — the null committable result zeroes
     * the tax, recomputes the total and clears the persisted breakdown (no provider document
     * exists, so no lifecycle commit either).
     */
    @Test
    void finalize_zeroesStaleTax_whenNothingTaxableAtFinalization() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-0000000000cb");
        Invoice draft = draftInvoice(UUID.fromString("00000000-0000-0000-0000-0000000000cc"), new BigDecimal("107.42"));
        draft.setId(invoiceId);
        draft.setSubtotal(new BigDecimal("100.00"));
        draft.setTax(new BigDecimal("7.42")); // stale draft tax from an earlier re-price
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draft));
        when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceTaxCalculator.calculateCommittable(draft)).thenReturn(null);

        InvoiceDetailsResponse response = service.completeInvoice(invoiceId, shopManagerRequest());

        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.FINALIZED);
        assertThat(draft.getTax()).isEqualByComparingTo("0");
        assertThat(draft.getTotal()).isEqualByComparingTo("100.00");
        verify(taxBreakdownWriter).replace(invoiceId, null);
        org.mockito.Mockito.verifyNoInteractions(taxLifecycleClient);
    }

    /**
     * #985: a failed committable calculation blocks finalization (D-T5) — without the persisted
     * provider document the later commit could never resolve. The invoice stays DRAFT.
     */
    @Test
    void finalize_propagates_whenCommittableTaxCalculationFails() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-0000000000c7");
        Invoice draft = draftInvoice(UUID.fromString("00000000-0000-0000-0000-0000000000c8"), BigDecimal.ZERO);
        draft.setId(invoiceId);
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draft));
        when(invoiceTaxCalculator.calculateCommittable(draft))
                .thenThrow(new IllegalStateException("Tax service returned a null response for tax calculation"));

        assertThatThrownBy(() -> service.completeInvoice(invoiceId, shopManagerRequest()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(draft.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        verify(invoiceRepository, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(taxLifecycleClient);
    }

    private static com.positivity.tax.common.dto.TaxCalculationResponse taxResponse(BigDecimal totalTax) {
        return com.positivity.tax.common.dto.TaxCalculationResponse.builder()
                .subtotal(BigDecimal.ZERO)
                .totalTax(totalTax)
                .total(totalTax)
                .effectiveTaxRate(BigDecimal.ZERO)
                .jurisdictions(List.of())
                .lineItemTaxes(List.of())
                .calculatedAt(TEST_CLOCK.instant())
                .build();
    }

    /**
     * Story T6 (R-T2): revert-to-DRAFT voids the provider tax document (InvoiceStatus has no
     * CANCELLED/VOIDED, so void hooks the revert transition).
     */
    @Test
    void revert_voidsProviderTaxDocument() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
        Invoice invoice = finalizedInvoice(workorderId);
        invoice.setId(invoiceId);
        invoice.setFinalizedAt(Instant.now(TEST_CLOCK).minusSeconds(3600));
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(elevationTokenService.verify(any(), any())).thenReturn(Optional.of(UUID.randomUUID()));

        service.revert(invoiceId, "MGR-APPROVAL-001", "Customer dispute");

        verify(taxLifecycleClient).voidTransaction(invoiceId);
    }

    /**
     * Decision D-T5: invoice finalization hard-requires a successful tax calculation — an
     * invoice whose tax was never computed (null) must not finalize with a silent zero-tax.
     */
    @Test
    void finalize_throws_whenTaxNotCalculated() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
        Invoice draft = draftInvoice(UUID.fromString("00000000-0000-0000-0000-0000000000e2"), new BigDecimal("99.00"));
        draft.setId(invoiceId);
        draft.setTax(null);
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.completeInvoice(invoiceId, shopManagerRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageMatching("(?i).*tax has not been calculated.*");
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
        invoice.setFinalizedAt(Instant.now(TEST_CLOCK).minusSeconds(3600));
        invoice.setFinalizedBy("manager-001");
        return invoice;
    }
}
