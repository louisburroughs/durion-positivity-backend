package com.positivity.inventory.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.inventory.internal.dto.returns.ReturnLineDto;
import com.positivity.inventory.internal.dto.returns.ReturnSubmitRequest;
import com.positivity.inventory.internal.dto.returns.ReturnableItemDto;
import com.positivity.inventory.internal.entity.ExtWorkorderPartReplica;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.exception.ReturnQuantityExceededException;
import com.positivity.inventory.internal.receiving.service.ReturnService;
import com.positivity.inventory.internal.repository.ExtWorkorderPartReplicaRepository;
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
 * Real Spring/H2 round trip for the returns-to-stock posting path (#2206): {@code
 * listReturnableItems} derives the returnable quantity from a real {@code WORKORDER_CONSUMPTION}
 * ledger row, and {@code submitToStock} persists the return record and posts a real {@code
 * RETURN_TO_STOCK} ledger row carrying {@code workorderId}/{@code workorderLineId} — no mocked
 * {@code ReturnService}, unlike {@link ReturnContractBehaviorIT}'s HTTP contract coverage.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Returns posting path, real Spring/H2 (#2206)")
class ReturnPostingPathIT {

    private static final String ACTOR = "return-posting-it";

    @Autowired
    private ReturnService returnService;

    @Autowired
    private ExtWorkorderPartReplicaRepository extWorkorderPartReplicaRepository;

    @Autowired
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @BeforeEach
    void setUp() {
        var authentication = new UsernamePasswordAuthenticationToken(ACTOR, "N/A", List.of());
        authentication.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, ACTOR));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("listReturnableItems derives quantityReturnable from a real WORKORDER_CONSUMPTION ledger row")
    void listReturnableItems_derivesFromRealLedgerRow() {
        UUID workorderId = UUID.randomUUID();
        UUID workorderLineId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        seedPartLine(workorderId, workorderLineId, productId);
        seedConsumption(workorderId, workorderLineId, productId, "5");

        List<ReturnableItemDto> items = returnService.listReturnableItems(workorderId);

        assertThat(items).hasSize(1);
        ReturnableItemDto item = items.get(0);
        assertThat(item.getWorkorderLineId()).isEqualTo(workorderLineId);
        assertThat(item.getSku()).isEqualTo(productId.toString());
        assertThat(item.getQuantityReturnable()).isEqualTo(5);
    }

    @Test
    @DisplayName(
            "submitToStock persists the return and posts a RETURN_TO_STOCK ledger row carrying the work order line")
    void submitToStock_postsRealLedgerRowAndPersistsReturn() {
        UUID workorderId = UUID.randomUUID();
        UUID workorderLineId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        seedPartLine(workorderId, workorderLineId, productId);
        seedConsumption(workorderId, workorderLineId, productId, "4");

        var result = returnService.submitToStock(ReturnSubmitRequest.builder()
                .workorderId(workorderId)
                .lines(List.of(ReturnLineDto.builder()
                        .itemId(workorderLineId)
                        .quantity(3)
                        .reasonCode("NOT_NEEDED")
                        .locationId(UUID.randomUUID())
                        .build()))
                .build());

        assertThat(result.getWorkorderId()).isEqualTo(workorderId);
        assertThat(result.getStatus()).isEqualTo("SUBMITTED");

        List<InventoryLedgerEntry> returnEntries = inventoryLedgerEntryRepository.findByWorkorderIdAndEventType(
                workorderId, InventoryLedgerEventType.RETURN_TO_STOCK);
        assertThat(returnEntries).hasSize(1);
        InventoryLedgerEntry posted = returnEntries.get(0);
        assertThat(posted.getWorkorderLineId()).isEqualTo(workorderLineId);
        assertThat(posted.getChangeInQuantity()).isEqualByComparingTo("3");
        assertThat(posted.getStockItemId()).isEqualTo(productId.toString());

        // Only 1 of the 4 consumed remains returnable now.
        List<ReturnableItemDto> afterFirstReturn = returnService.listReturnableItems(workorderId);
        assertThat(afterFirstReturn.get(0).getQuantityReturnable()).isEqualTo(1);
    }

    @Test
    @DisplayName("submitToStock rejects a quantity exceeding what remains returnable, posting nothing")
    void submitToStock_quantityExceedsReturnable_rejectsWithNoPosting() {
        UUID workorderId = UUID.randomUUID();
        UUID workorderLineId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        seedPartLine(workorderId, workorderLineId, productId);
        seedConsumption(workorderId, workorderLineId, productId, "1");

        ReturnSubmitRequest request = ReturnSubmitRequest.builder()
                .workorderId(workorderId)
                .lines(List.of(ReturnLineDto.builder()
                        .itemId(workorderLineId)
                        .quantity(5)
                        .reasonCode("WRONG_PART")
                        .locationId(UUID.randomUUID())
                        .build()))
                .build();

        assertThatThrownBy(() -> returnService.submitToStock(request))
                .isInstanceOf(ReturnQuantityExceededException.class);

        assertThat(inventoryLedgerEntryRepository.findByWorkorderIdAndEventType(
                        workorderId, InventoryLedgerEventType.RETURN_TO_STOCK))
                .isEmpty();
    }

    private void seedPartLine(UUID workorderId, UUID workorderLineId, UUID productId) {
        extWorkorderPartReplicaRepository.save(ExtWorkorderPartReplica.builder()
                .workorderLineId(workorderLineId)
                .workorderId(workorderId)
                .productEntityId(productId)
                .quantity(new BigDecimal("10"))
                .build());
    }

    private void seedConsumption(UUID workorderId, UUID workorderLineId, UUID productId, String quantity) {
        inventoryLedgerEntryRepository.save(InventoryLedgerEntry.builder()
                .stockItemId(productId.toString())
                .eventType(InventoryLedgerEventType.WORKORDER_CONSUMPTION)
                .changeInQuantity(new BigDecimal(quantity).negate())
                .quantityAfter(BigDecimal.ZERO)
                .workorderId(workorderId)
                .workorderLineId(workorderLineId)
                .transactionUserId(ACTOR)
                .build());
    }
}
