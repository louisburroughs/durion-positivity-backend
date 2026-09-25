package com.positivity.inventory.internal.movement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.inventory.internal.dto.CreateAdjustmentRequestDto;
import com.positivity.inventory.internal.entity.InventoryAdjustmentRequest;
import com.positivity.inventory.internal.enums.AdjustmentRequestStatus;
import com.positivity.inventory.internal.exception.InventoryValidationException;
import com.positivity.inventory.internal.exception.ZeroQuantityAdjustmentException;
import com.positivity.inventory.internal.repository.InventoryAdjustmentRequestRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.security.common.GatewaySecurityConstants;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * #2201 through the real transaction interceptor: approving a zero-quantity request stored before
 * create-time validation existed answers 422, and the {@code REJECTED} status it sets survives that
 * exception ({@code noRollbackFor}) while nothing reaches the ledger.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Zero-quantity adjustment approval — transaction outcome (#2201)")
class ZeroQuantityApprovalTransactionTest {

    private static final String ACTOR = "zero-qty-approver";

    @Autowired
    private StockMovementService stockMovementService;

    @Autowired
    private InventoryAdjustmentRequestRepository adjustmentRepository;

    @Autowired
    private InventoryLedgerEntryRepository ledgerRepository;

    @BeforeEach
    void authenticate() {
        var authentication = new UsernamePasswordAuthenticationToken(ACTOR, "N/A", List.of());
        authentication.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, ACTOR));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a stored zero-quantity request is REJECTED durably and posts nothing")
    void approvingStoredZeroRequest_commitsRejectionAndPostsNothing() {
        String sku = "SKU-ZERO-" + UUID.randomUUID();
        InventoryAdjustmentRequest legacy = adjustmentRepository.save(InventoryAdjustmentRequest.builder()
                .productSku(sku)
                .locationId(UUID.randomUUID())
                .quantity(BigDecimal.ZERO)
                .reasonCode("CYCLE_COUNT")
                .status(AdjustmentRequestStatus.PENDING)
                .requestedByUserId("legacy-requester")
                .requestedAt(Instant.parse("2026-09-01T00:00:00Z"))
                .build());

        assertThatThrownBy(() -> stockMovementService.approveAdjustmentRequest(legacy.getAdjustmentRequestId(), ACTOR))
                .isInstanceOf(ZeroQuantityAdjustmentException.class);

        assertThat(adjustmentRepository.findById(legacy.getAdjustmentRequestId()))
                .hasValueSatisfying(
                        stored -> assertThat(stored.getStatus()).isEqualTo(AdjustmentRequestStatus.REJECTED));
        assertThat(ledgerRepository.findAll()).noneMatch(entry -> sku.equals(entry.getStockItemId()));
    }

    @Test
    @DisplayName("creating a zero-quantity request through the service (bulk ingest's path) is refused")
    void creatingZeroRequestThroughService_isRefused() {
        String sku = "SKU-ZERO-" + UUID.randomUUID();
        CreateAdjustmentRequestDto request = CreateAdjustmentRequestDto.builder()
                .productSku(sku)
                .locationId(UUID.randomUUID())
                .quantity(new BigDecimal("0.00"))
                .reasonCode("CYCLE_COUNT")
                .build();

        assertThatThrownBy(() -> stockMovementService.createAdjustmentRequest(request, ACTOR))
                .isInstanceOf(InventoryValidationException.class)
                .hasMessage("quantity must not be zero");
        assertThat(adjustmentRepository.findAll()).noneMatch(stored -> sku.equals(stored.getProductSku()));
    }
}
