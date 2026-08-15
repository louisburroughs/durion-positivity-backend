package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.dto.ItemCostAuditDto;
import com.positivity.catalog.internal.dto.ItemCostsDto;
import com.positivity.catalog.internal.dto.PurchaseOrderReceivedEventDto;
import com.positivity.catalog.internal.dto.UpdateStandardCostRequestDto;
import com.positivity.catalog.internal.entity.ItemCostAuditEntity;
import com.positivity.catalog.internal.entity.ItemCostEntity;
import com.positivity.catalog.internal.enums.ChangeSourceType;
import com.positivity.catalog.internal.enums.CostType;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.ItemCostAuditRepository;
import com.positivity.catalog.internal.repository.ItemCostRepository;
import com.positivity.security.common.GatewaySecurityConstants;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Item costing: manual standard-cost edits are audited against the acting user, and received
 * purchase orders roll the last cost and the weighted-average cost forward.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ItemCostServiceImpl")
class ItemCostServiceImplTest {

    private static final UUID ITEM_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3faa01");
    private static final UUID AUDIT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3faa02");

    @Mock
    private ItemCostRepository itemCostRepository;

    @Mock
    private ItemCostAuditRepository itemCostAuditRepository;

    @InjectMocks
    private ItemCostServiceImpl service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void stubSaves() {
        when(itemCostRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemCostAuditRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static void authenticateAs(String username) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(username, "n/a", List.of());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static ItemCostEntity itemCost(String standard, String last, String average, String qtyOnHand) {
        ItemCostEntity entity = new ItemCostEntity();
        entity.setItemId(ITEM_ID);
        entity.setStandardCost(standard == null ? null : new BigDecimal(standard));
        entity.setLastCost(last == null ? null : new BigDecimal(last));
        entity.setAverageCost(average == null ? null : new BigDecimal(average));
        entity.setQtyOnHand(qtyOnHand == null ? null : new BigDecimal(qtyOnHand));
        return entity;
    }

    private static UpdateStandardCostRequestDto costRequest(String newCost, String reasonCode) {
        UpdateStandardCostRequestDto request = new UpdateStandardCostRequestDto();
        request.setNewCost(newCost == null ? null : new BigDecimal(newCost));
        request.setReasonCode(reasonCode);
        return request;
    }

    private static PurchaseOrderReceivedEventDto poEvent(String qty, String unitCost) {
        PurchaseOrderReceivedEventDto event = new PurchaseOrderReceivedEventDto();
        event.setItemId(ITEM_ID);
        event.setPurchaseOrderId("PO-1001");
        event.setReceivedQty(qty == null ? null : new BigDecimal(qty));
        event.setReceivedUnitCost(unitCost == null ? null : new BigDecimal(unitCost));
        return event;
    }

    @Nested
    @DisplayName("updateStandardCost")
    class UpdateStandardCost {

        @Test
        void writesTheNewCostAtFourDecimalPlacesAndAuditsTheChange() {
            when(itemCostRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(itemCost("40", null, null, "5")));
            authenticateAs("jane.smith");

            ItemCostsDto response = service.updateStandardCost(ITEM_ID, costRequest("42.125", "SUPPLIER_INCREASE"));

            assertThat(response.getStandardCost()).isEqualByComparingTo("42.1250");
            ArgumentCaptor<ItemCostAuditEntity> audit = ArgumentCaptor.forClass(ItemCostAuditEntity.class);
            verify(itemCostAuditRepository).save(audit.capture());
            assertThat(audit.getValue().getCostTypeChanged()).isEqualTo(CostType.STANDARD);
            assertThat(audit.getValue().getOldValue()).isEqualByComparingTo("40");
            assertThat(audit.getValue().getNewValue()).isEqualByComparingTo("42.1250");
            assertThat(audit.getValue().getChangeSourceType()).isEqualTo(ChangeSourceType.MANUAL);
            assertThat(audit.getValue().getChangeSourceId()).isEqualTo("MANUAL");
            assertThat(audit.getValue().getActor()).isEqualTo("jane.smith");
            assertThat(audit.getValue().getReasonCode()).isEqualTo("SUPPLIER_INCREASE");
        }

        @Test
        void createsTheCostRowForAnItemThatHasNoneYet() {
            when(itemCostRepository.findByItemId(ITEM_ID)).thenReturn(Optional.empty());

            ItemCostsDto response = service.updateStandardCost(ITEM_ID, costRequest("10", "INITIAL"));

            assertThat(response.getItemId()).isEqualTo(ITEM_ID);
            assertThat(response.getStandardCost()).isEqualByComparingTo("10.0000");
            ArgumentCaptor<ItemCostEntity> saved = ArgumentCaptor.forClass(ItemCostEntity.class);
            verify(itemCostRepository).save(saved.capture());
            assertThat(saved.getValue().getQtyOnHand()).isEqualByComparingTo("0");
        }

        @Test
        void attributesTheChangeToTheSystemWhenNobodyIsAuthenticated() {
            when(itemCostRepository.findByItemId(ITEM_ID)).thenReturn(Optional.empty());

            service.updateStandardCost(ITEM_ID, costRequest("10", "INITIAL"));

            ArgumentCaptor<ItemCostAuditEntity> audit = ArgumentCaptor.forClass(ItemCostAuditEntity.class);
            verify(itemCostAuditRepository).save(audit.capture());
            assertThat(audit.getValue().getActor()).isEqualTo("system");
        }

        @Test
        void rejectsAnEditWithNoReasonCode() {
            UpdateStandardCostRequestDto request = costRequest("10", "  ");

            assertThatThrownBy(() -> service.updateStandardCost(ITEM_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("reasonCode is required");
            verifyNoInteractions(itemCostRepository);
        }

        @Test
        void rejectsAnEditWithAMissingReasonCode() {
            UpdateStandardCostRequestDto request = costRequest("10", null);

            assertThatThrownBy(() -> service.updateStandardCost(ITEM_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("reasonCode is required");
        }

        @Test
        void rejectsANonPositiveCost() {
            UpdateStandardCostRequestDto request = costRequest("0", "INITIAL");

            assertThatThrownBy(() -> service.updateStandardCost(ITEM_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("newCost must be positive");
        }

        @Test
        void rejectsAMissingCost() {
            UpdateStandardCostRequestDto request = costRequest(null, "INITIAL");

            assertThatThrownBy(() -> service.updateStandardCost(ITEM_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("newCost must be positive");
        }
    }

    @Nested
    @DisplayName("getItemCosts / getAuditHistory")
    class Reads {

        @Test
        void reportsTheStoredCosts() {
            when(itemCostRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(itemCost("40", "41", "40.5", "5")));

            ItemCostsDto response = service.getItemCosts(ITEM_ID);

            assertThat(response.getStandardCost()).isEqualByComparingTo("40");
            assertThat(response.getLastCost()).isEqualByComparingTo("41");
            assertThat(response.getAverageCost()).isEqualByComparingTo("40.5");
        }

        @Test
        void reportsAnEmptyCostSheetForAnItemWithNoCostRow() {
            when(itemCostRepository.findByItemId(ITEM_ID)).thenReturn(Optional.empty());

            ItemCostsDto response = service.getItemCosts(ITEM_ID);

            assertThat(response.getItemId()).isEqualTo(ITEM_ID);
            assertThat(response.getStandardCost()).isNull();
        }

        @Test
        void mapsTheAuditTrailNewestFirst() {
            ItemCostAuditEntity audit = new ItemCostAuditEntity();
            audit.setAuditId(AUDIT_ID);
            audit.setItemId(ITEM_ID);
            audit.setTimestamp(Instant.parse("2026-03-01T12:00:00Z"));
            audit.setCostTypeChanged(CostType.AVERAGE);
            audit.setOldValue(new BigDecimal("40"));
            audit.setNewValue(new BigDecimal("41"));
            audit.setChangeSourceType(ChangeSourceType.PURCHASE_ORDER);
            audit.setChangeSourceId("PO-1001");
            audit.setActor("system");
            audit.setReasonCode("RECEIPT");
            when(itemCostAuditRepository.findByItemIdOrderByTimestampDesc(ITEM_ID))
                    .thenReturn(List.of(audit));

            List<ItemCostAuditDto> history = service.getAuditHistory(ITEM_ID);

            assertThat(history).hasSize(1);
            assertThat(history.get(0).getAuditId()).isEqualTo(AUDIT_ID);
            assertThat(history.get(0).getCostTypeChanged()).isEqualTo(CostType.AVERAGE);
            assertThat(history.get(0).getChangeSourceId()).isEqualTo("PO-1001");
            assertThat(history.get(0).getReasonCode()).isEqualTo("RECEIPT");
        }
    }

    @Nested
    @DisplayName("processPoReceivedEvent")
    class ProcessPoReceivedEvent {

        @Test
        void rollsTheWeightedAverageForwardAndRecordsBothCostChanges() {
            ItemCostEntity existing = itemCost(null, "38", "40", "10");
            when(itemCostRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(existing));

            service.processPoReceivedEvent(poEvent("10", "50"));

            // (10 × 40 + 10 × 50) / 20 = 45
            assertThat(existing.getAverageCost()).isEqualByComparingTo("45.0000");
            assertThat(existing.getLastCost()).isEqualByComparingTo("50.0000");
            assertThat(existing.getQtyOnHand()).isEqualByComparingTo("20");

            ArgumentCaptor<ItemCostAuditEntity> audits = ArgumentCaptor.forClass(ItemCostAuditEntity.class);
            verify(itemCostAuditRepository, org.mockito.Mockito.times(2)).save(audits.capture());
            assertThat(audits.getAllValues())
                    .extracting(ItemCostAuditEntity::getCostTypeChanged)
                    .containsExactly(CostType.LAST, CostType.AVERAGE);
            assertThat(audits.getAllValues())
                    .allSatisfy(audit -> assertThat(audit.getChangeSourceId()).isEqualTo("PO-1001"));
            assertThat(audits.getAllValues().get(0).getActor()).isEqualTo("system");
        }

        @Test
        void treatsAFirstReceiptAsTheOpeningAverage() {
            when(itemCostRepository.findByItemId(ITEM_ID)).thenReturn(Optional.empty());

            service.processPoReceivedEvent(poEvent("4", "25"));

            ArgumentCaptor<ItemCostEntity> saved = ArgumentCaptor.forClass(ItemCostEntity.class);
            verify(itemCostRepository).save(saved.capture());
            assertThat(saved.getValue().getAverageCost()).isEqualByComparingTo("25.0000");
            assertThat(saved.getValue().getQtyOnHand()).isEqualByComparingTo("4");
        }

        @Test
        void treatsAMissingQuantityOnHandAsZero() {
            ItemCostEntity existing = itemCost(null, null, null, null);
            when(itemCostRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(existing));

            service.processPoReceivedEvent(poEvent("2", "30"));

            assertThat(existing.getQtyOnHand()).isEqualByComparingTo("2");
            assertThat(existing.getAverageCost()).isEqualByComparingTo("30.0000");
        }

        @Test
        void ignoresAnEventWithANonPositiveUnitCost() {
            service.processPoReceivedEvent(poEvent("10", "0"));

            verify(itemCostRepository, never()).save(any());
            verify(itemCostAuditRepository, never()).save(any());
        }

        @Test
        void ignoresAnEventWithNoUnitCost() {
            service.processPoReceivedEvent(poEvent("10", null));

            verify(itemCostRepository, never()).save(any());
        }

        @Test
        void ignoresAnEventWithANonPositiveQuantity() {
            service.processPoReceivedEvent(poEvent("0", "10"));

            verify(itemCostRepository, never()).save(any());
        }

        @Test
        void ignoresAnEventWithNoQuantity() {
            service.processPoReceivedEvent(poEvent(null, "10"));

            verify(itemCostRepository, never()).save(any());
        }
    }
}
