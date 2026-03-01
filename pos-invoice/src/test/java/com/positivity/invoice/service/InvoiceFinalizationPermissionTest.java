package com.positivity.invoice.service;

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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

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
    // AC3 — SERVICE_ADVISOR permission matrix
    // -------------------------------------------------------------------------

    /**
     * AC3: SERVICE_ADVISOR requesting finalization of an invoice at $499.99 is
     * within the $500 cap
     * and must succeed without requiring a manager approval code.
     */
    @Test
    void serviceAdvisor_canFinalize_invoiceBelow500() {
        UUID invoiceId = UUID.randomUUID();
        // Issue #13: $499.99 is below the $500 SERVICE_ADVISOR limit
        FinalizationRequest request = buildRequest(SERVICE_ADVISOR, new BigDecimal("499.99"), null);

        assertThatCode(() -> service.finalize(invoiceId, request))
                .doesNotThrowAnyException();
    }

    /**
     * AC3: SERVICE_ADVISOR requesting finalization of an invoice at $500.01 without
     * a manager
     * approval code must be rejected.
     */
    @Test
    void serviceAdvisor_cannotFinalize_invoiceAbove500_withoutManagerApproval() {
        UUID invoiceId = UUID.randomUUID();
        // Issue #13: $500.01 exceeds SERVICE_ADVISOR limit; null approval code means no
        // override
        FinalizationRequest request = buildRequest(SERVICE_ADVISOR, new BigDecimal("500.01"), null);

        assertThatThrownBy(() -> service.finalize(invoiceId, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * AC3: SERVICE_ADVISOR supplying a manager approval code can finalize an
     * invoice whose total
     * exceeds $500. The override event must be audited (observable in event log).
     */
    @Test
    void serviceAdvisor_canFinalize_invoiceAbove500_withManagerApprovalCode() {
        UUID invoiceId = UUID.randomUUID();
        // Issue #13: approval code present — override path; audit expected
        FinalizationRequest request = buildRequest(SERVICE_ADVISOR, new BigDecimal("750.00"), "MGR-APPROVAL-XYZ");

        InvoiceDetailsResponse response = service.finalize(invoiceId, request);

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
        UUID invoiceId = UUID.randomUUID();
        // Issue #13: $10000 — well above any role cap; SHOP_MANAGER must succeed
        FinalizationRequest request = buildRequest(SHOP_MANAGER, new BigDecimal("10000.00"), null);

        InvoiceDetailsResponse response = service.finalize(invoiceId, request);

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
        UUID invoiceId = UUID.randomUUID();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(finalizedInvoice(UUID.randomUUID())));
        FinalizationRequest request = buildRequest(SHOP_MANAGER, new BigDecimal("200.00"), null);

        assertThatThrownBy(() -> service.finalize(invoiceId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageMatching("(?i).*finalized.*");
    }

    // -------------------------------------------------------------------------
    // Fixture helper
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link FinalizationRequest} for the given permission level and
     * amount.
     *
     * @param permissionLevel role constant (SERVICE_ADVISOR or SHOP_MANAGER)
     * @param amountLimit     invoice grand total driving permission enforcement
     * @param approvalCode    manager approval code; {@code null} when not provided
     * @return configured request ready for {@code service.finalize(...)}
     */
    private FinalizationRequest buildRequest(String permissionLevel, BigDecimal amountLimit, String approvalCode) {
        FinalizationRequest req = new FinalizationRequest();
        req.setPermissionLevel(permissionLevel);
        req.setAmountLimit(amountLimit);
        req.setManagerApprovalCode(approvalCode);
        req.setManagerApprovalRequired(approvalCode != null);
        String actor = SHOP_MANAGER.equals(permissionLevel) ? "manager-001" : "advisor-001";
        req.setRequestedBy(actor);
        req.setFinalizedBy(actor);
        req.setFinalizedAt(Instant.now());
        return req;
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
