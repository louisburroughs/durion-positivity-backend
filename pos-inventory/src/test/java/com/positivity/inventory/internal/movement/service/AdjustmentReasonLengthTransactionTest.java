package com.positivity.inventory.internal.movement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.inventory.internal.dto.AdjustmentRequestResponse;
import com.positivity.inventory.internal.dto.CreateAdjustmentRequestDto;
import com.positivity.inventory.internal.exception.InventoryValidationException;
import com.positivity.inventory.internal.repository.InventoryAdjustmentRequestRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.security.common.GatewaySecurityConstants;
import java.math.BigDecimal;
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
 * #2628 against the stored columns (entity-mapped, the same width as the V1 baseline): a manual adjustment's reason of exactly 100 characters is stored on
 * the request and copied to the ledger row untruncated, and a longer one (the bulk-ingest path, which
 * skips bean validation) is refused before anything is written instead of failing at the insert.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Manual adjustment reason length — stored columns (#2628)")
class AdjustmentReasonLengthTransactionTest {

    private static final String ACTOR = "reason-length-actor";

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

    private static CreateAdjustmentRequestDto request(String sku, String reasonCode) {
        return CreateAdjustmentRequestDto.builder()
                .productSku(sku)
                .locationId(UUID.randomUUID())
                .quantity(new BigDecimal("3"))
                .reasonCode(reasonCode)
                .build();
    }

    @Test
    @DisplayName("a 100-character reason is stored on the request and the ledger row unchanged")
    void reasonAtLimit_isStoredAndPostedUnchanged() {
        String sku = "SKU-REASON-" + UUID.randomUUID();
        String reason = "VENDOR_RETURN:RMA-2026-0042:GR-000123:" + "X".repeat(62);

        AdjustmentRequestResponse created = stockMovementService.createAdjustmentRequest(request(sku, reason), ACTOR);
        stockMovementService.approveAdjustmentRequest(created.getAdjustmentRequestId(), ACTOR);

        assertThat(reason).hasSize(CreateAdjustmentRequestDto.REASON_CODE_MAX_LENGTH);
        assertThat(adjustmentRepository.findById(created.getAdjustmentRequestId()))
                .hasValueSatisfying(stored -> assertThat(stored.getReasonCode()).isEqualTo(reason));
        assertThat(ledgerRepository.findAll())
                .filteredOn(entry -> sku.equals(entry.getStockItemId()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getReasonCode()).isEqualTo(reason));
    }

    @Test
    @DisplayName("a 101-character reason through the service (bulk ingest's path) is refused and writes nothing")
    void reasonOverLimit_isRefusedAndWritesNothing() {
        String sku = "SKU-REASON-" + UUID.randomUUID();
        CreateAdjustmentRequestDto request = request(sku, "R".repeat(101));

        assertThatThrownBy(() -> stockMovementService.createAdjustmentRequest(request, ACTOR))
                .isInstanceOf(InventoryValidationException.class)
                .hasMessage("reasonCode must be at most 100 characters");
        assertThat(adjustmentRepository.findAll()).noneMatch(stored -> sku.equals(stored.getProductSku()));
    }
}
