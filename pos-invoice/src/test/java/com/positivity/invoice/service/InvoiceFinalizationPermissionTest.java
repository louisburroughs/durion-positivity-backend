package com.positivity.invoice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.invoice.internal.dto.FinalizationRequest;
import com.positivity.invoice.internal.dto.InvoiceDetailsResponse;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.repository.InvoiceRepository;
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
 * Permission matrix unit tests for {@link InvoiceFinalizationService} — Story
 * #13, AC3.
 *
 * <p>
 * Verifies the full permission matrix:
 * <ul>
 * <li>SERVICE_ADVISOR: allowed ≤ $500, blocked {@literal >} $500 without
 * manager code</li>
 * <li>SERVICE_ADVISOR: allowed {@literal >} $500 when a valid manager approval
 * code is present (audited)</li>
 * <li>SHOP_MANAGER: unlimited amount, no approval code required</li>
 * <li>Idempotency guard: already-FINALIZED invoice must not be
 * double-finalized</li>
 * </ul>
 *
 * Issue: #13
 */
@ExtendWith(MockitoExtension.class)
class InvoiceFinalizationPermissionTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    /**
     * Repository mock — will be needed by the real implementation for invoice
     * lookup.
     */
    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private InvoiceFinalizationServiceImpl service;

    @BeforeEach
    void setUpSecurityContext() {
        withServiceAdvisorContext();
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
     * Sets up a SHOP_MANAGER role in the security context for tests that need it.
     */
    private void withShopManagerContext() {
        var auth = new UsernamePasswordAuthenticationToken(
                "manager-001", null, List.of(new SimpleGrantedAuthority("ROLE_SHOP_MANAGER")));
        auth.setDetails(java.util.Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "manager-001"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // -------------------------------------------------------------------------
    // AC3 — SERVICE_ADVISOR permission matrix
    // -------------------------------------------------------------------------

    /**
     * AC3: SERVICE_ADVISOR requesting finalization of an invoice at $499.99 is
     * within the $500 cap
     * and must succeed without requiring a manager approval code.
     */
    @Test
    void serviceAdvisor_canFinalize_invoiceBelow500() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        // SERVICE_ADVISOR context is configured in @BeforeEach; $499.99 within cap
        when(invoiceRepository.findById(invoiceId))
                .thenReturn(Optional.of(draftInvoice(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"), new BigDecimal("499.99"))));
        when(invoiceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        FinalizationRequest request = buildRequest(null);

        assertThatCode(() -> service.completeInvoice(invoiceId, request)).doesNotThrowAnyException();
    }

    /**
     * AC3: SERVICE_ADVISOR requesting finalization of an invoice at $500.01 without
     * a manager
     * approval code must be rejected.
     */
    @Test
    void serviceAdvisor_cannotFinalize_invoiceAbove500_withoutManagerApproval() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        // SERVICE_ADVISOR context; invoice total $500.01 > cap; no approval code
        when(invoiceRepository.findById(invoiceId))
                .thenReturn(Optional.of(draftInvoice(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"), new BigDecimal("500.01"))));
        FinalizationRequest request = buildRequest(null);

        assertThatThrownBy(() -> service.completeInvoice(invoiceId, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * AC3: SERVICE_ADVISOR supplying a manager approval code can finalize an
     * invoice whose total
     * exceeds $500. The override event must be audited (observable in event log).
     */
    @Test
    void serviceAdvisor_canFinalize_invoiceAbove500_withManagerApprovalCode() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        // SERVICE_ADVISOR with approval code; invoice $750 > cap → allowed (override
        // audited)
        when(invoiceRepository.findById(invoiceId))
                .thenReturn(Optional.of(draftInvoice(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"), new BigDecimal("750.00"))));
        when(invoiceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        FinalizationRequest request = buildRequest("MGR-APPROVAL-XYZ");

        InvoiceDetailsResponse response = service.completeInvoice(invoiceId, request);

        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.FINALIZED);
    }

    // -------------------------------------------------------------------------
    // AC3 — SHOP_MANAGER permission matrix
    // -------------------------------------------------------------------------

    /**
     * AC3: SHOP_MANAGER has unlimited finalization authority — a $10,000 invoice
     * must be
     * approved without any manager approval code.
     */
    @Test
    void shopManager_canFinalize_invoiceAboveAnyAmount() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        // C1: SHOP_MANAGER role in SecurityContext → bypasses all amount caps
        withShopManagerContext();
        when(invoiceRepository.findById(invoiceId))
                .thenReturn(Optional.of(draftInvoice(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"), new BigDecimal("10000.00"))));
        when(invoiceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        FinalizationRequest request = buildRequest(null);

        InvoiceDetailsResponse response = service.completeInvoice(invoiceId, request);

        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.FINALIZED);
    }

    // -------------------------------------------------------------------------
    // AC4 — Idempotency guard
    // -------------------------------------------------------------------------

    /**
     * AC4 guard: Calling {@code finalize} on an already-FINALIZED invoice must
     * throw
     * {@link IllegalStateException} referencing the finalized state to prevent
     * double-finalization.
     */
    @Test
    void finalization_isIdempotent_whenInvoiceAlreadyFinalized() {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(invoiceRepository.findById(invoiceId))
                .thenReturn(Optional.of(finalizedInvoice(UUID.fromString("00000000-0000-0000-0000-000000000001"))));
        FinalizationRequest request = buildRequest(null);

        assertThatThrownBy(() -> service.completeInvoice(invoiceId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageMatching("(?i).*finalized.*");
    }

    // -------------------------------------------------------------------------
    // Fixture helper
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link FinalizationRequest} with the given approval code.
     *
     * <p>
     * Role is derived from {@code SecurityContext} at call time (ADR-0018),
     * not from this request.
     *
     * @param approvalCode manager approval code; {@code null} when not provided
     * @return configured request
     */
    private FinalizationRequest buildRequest(String approvalCode) {
        FinalizationRequest req = new FinalizationRequest();
        req.setManagerApprovalCode(approvalCode);
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
        invoice.setFinalizedAt(Instant.now(TEST_CLOCK).minusSeconds(3600));
        invoice.setFinalizedBy("manager-001");
        return invoice;
    }
}
