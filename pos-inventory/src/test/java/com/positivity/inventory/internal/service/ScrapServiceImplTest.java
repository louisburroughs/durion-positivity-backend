package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.inventory.ScrapPostedV1;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentTaskResponse;
import com.positivity.inventory.internal.dto.scrap.ApproveScrapRequest;
import com.positivity.inventory.internal.dto.scrap.CreateScrapRequest;
import com.positivity.inventory.internal.dto.scrap.RejectScrapRequest;
import com.positivity.inventory.internal.dto.scrap.ScrapResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.ScrapRecord;
import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.ScrapCostSource;
import com.positivity.inventory.internal.enums.ScrapReasonCode;
import com.positivity.inventory.internal.enums.ScrapStatus;
import com.positivity.inventory.internal.exception.InsufficientPermissionException;
import com.positivity.inventory.internal.exception.NegativeStockPolicyViolationException;
import com.positivity.inventory.internal.exception.ScrapInsufficientStockException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.ScrapRecordRepository;
import com.positivity.inventory.service.ApprovalThresholdEvaluator;
import com.positivity.security.common.GatewaySecurityConstants;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link ScrapServiceImpl} (odoo-parity D1, issue #1030): value-threshold
 * evaluation, interim cost snapshot, OTHER-requires-notes, and posting/override behavior.
 */
@ExtendWith(MockitoExtension.class)
class ScrapServiceImplTest {

    private static final String ACTOR = "scrap-user";
    private static final String SKU = "OIL-5W30-5QT";
    private static final UUID LOCATION_ID = UUID.fromString("01960004-0001-7000-8000-000000000001");

    @Mock
    private ScrapRecordRepository scrapRepository;

    @Mock
    private InventoryLedgerEntryRepository ledgerRepository;

    @Mock
    private LedgerPostingService ledgerPostingService;

    @Mock
    private ApprovalThresholdEvaluator thresholdEvaluator;

    @Mock
    private InventoryFactPublisher inventoryFactPublisher;

    @Mock
    private com.positivity.inventory.service.ReplenishmentService replenishmentService;

    @Mock
    private CostingMethodResolver methodResolver;

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC);

    private ScrapServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ScrapServiceImpl(
                scrapRepository,
                ledgerRepository,
                ledgerPostingService,
                thresholdEvaluator,
                inventoryFactPublisher,
                replenishmentService,
                fixedClock,
                methodResolver);
        setUpAuthenticatedActor("inventory:scrap:create");
        // odoo-parity J3: the fact labels costSource with the resolved costing method whenever the
        // engine stamped a cost. Default AVERAGE; STANDARD-specific tests override this.
        lenient().when(methodResolver.resolve(SKU)).thenReturn(CostingMethod.AVERAGE);
        lenient().when(scrapRepository.save(any(ScrapRecord.class))).thenAnswer(invocation -> {
            ScrapRecord record = invocation.getArgument(0);
            if (record.getScrapId() == null) {
                record.setScrapId(UUID.randomUUID());
            }
            return record;
        });
        lenient()
                .when(ledgerRepository.calculateOnHandQuantityAtLocation(SKU, LOCATION_ID))
                .thenReturn(10);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("OTHER reason without notes is rejected")
    void createScrap_otherReasonWithoutNotes_throws() {
        CreateScrapRequest request = createRequest(ScrapReasonCode.OTHER, null);

        assertThatThrownBy(() -> service.createScrap(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Notes are required");
        verifyNoInteractions(ledgerPostingService);
    }

    @Test
    @DisplayName("Cost snapshot prefers the latest GOODS_RECEIPT cost, labeled LATEST_RECEIPT")
    void createScrap_snapshotsLatestReceiptCost() {
        stubCosts(new BigDecimal("9.9900"), null);
        stubBelowThreshold();
        stubPostingSuccess();

        ScrapResponse response = service.createScrap(createRequest(ScrapReasonCode.DAMAGED, null));

        assertThat(response.getUnitCostSnapshot()).isEqualByComparingTo("9.99");
        assertThat(response.getCostSource()).isEqualTo(ScrapCostSource.LATEST_RECEIPT);
        assertThat(response.getScrapValue()).isEqualByComparingTo("29.97");
        verify(thresholdEvaluator).evaluateScrapApprovalTier(new BigDecimal("29.9700"));
    }

    @Test
    @DisplayName("Cost snapshot falls back to the latest non-null cost of any entry")
    void createScrap_fallsBackToAnyLatestCost() {
        stubCosts(null, new BigDecimal("7.5000"));
        stubBelowThreshold();
        stubPostingSuccess();

        ScrapResponse response = service.createScrap(createRequest(ScrapReasonCode.EXPIRED, null));

        assertThat(response.getUnitCostSnapshot()).isEqualByComparingTo("7.50");
        assertThat(response.getCostSource()).isEqualTo(ScrapCostSource.LATEST_RECEIPT);
    }

    @Test
    @DisplayName("Unknown cost means unknown value: approval required at TIER_1_MANAGER, no auto-post")
    void createScrap_unknownCost_requiresTier1Approval() {
        stubCosts(null, null);

        ScrapResponse response = service.createScrap(createRequest(ScrapReasonCode.LOST, null));

        assertThat(response.getStatus()).isEqualTo(ScrapStatus.PENDING_APPROVAL);
        assertThat(response.getRequiredApprovalTier()).isEqualTo(ApprovalTier.TIER_1_MANAGER);
        assertThat(response.getCostSource()).isEqualTo(ScrapCostSource.NONE);
        assertThat(response.getUnitCostSnapshot()).isNull();
        verifyNoInteractions(thresholdEvaluator);
        verifyNoInteractions(ledgerPostingService);
    }

    @Test
    @DisplayName("Below threshold: auto-approved and posted as SCRAP_OUT with sourceTransactionId = scrapId")
    void createScrap_belowThreshold_autoPostsScrapOut() {
        stubCosts(new BigDecimal("2.0000"), null);
        stubBelowThreshold();
        UUID ledgerEntryId = stubPostingSuccess();
        when(ledgerRepository.calculateOnHandQuantityAtLocation(SKU, LOCATION_ID))
                .thenReturn(10);

        ScrapResponse response = service.createScrap(createRequest(ScrapReasonCode.DAMAGED, null));

        assertThat(response.getStatus()).isEqualTo(ScrapStatus.POSTED);
        assertThat(response.getApprovedBy()).isEqualTo("SYSTEM");
        assertThat(response.getLedgerEntryId()).isEqualTo(ledgerEntryId);

        ArgumentCaptor<InventoryLedgerEntry> entryCaptor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(ledgerPostingService).post(entryCaptor.capture(), eq(false));
        InventoryLedgerEntry entry = entryCaptor.getValue();
        assertThat(entry.getEventType()).isEqualTo(InventoryLedgerEventType.SCRAP_OUT);
        assertThat(entry.getChangeInQuantity()).isEqualTo(-3);
        assertThat(entry.getQuantityAfter()).isEqualTo(7);
        assertThat(entry.getReasonCode()).isEqualTo("DAMAGED");
        assertThat(entry.getSourceTransactionId())
                .isEqualTo(response.getScrapId().toString());
        assertThat(entry.getUnitCost()).isEqualByComparingTo("2.00");

        ArgumentCaptor<ScrapPostedV1> factCaptor = ArgumentCaptor.forClass(ScrapPostedV1.class);
        verify(inventoryFactPublisher).recordScrapPosted(factCaptor.capture());
        assertThat(factCaptor.getValue().sku()).isEqualTo(SKU);
        assertThat(factCaptor.getValue().quantity()).isEqualTo(3);
        // odoo-parity J3: fact cost + source come from the engine, labeled by the resolved method.
        assertThat(factCaptor.getValue().costSource()).isEqualTo("AVERAGE");
    }

    @Test
    @DisplayName("J3: fact carries the engine-stamped SCRAP_OUT cost, not the interim snapshot; AVERAGE method")
    void createScrap_factCarriesEngineCost_avco() {
        // Interim snapshot (2.00) gates the approval tier; the engine stamps a distinct 2.50 on the
        // SCRAP_OUT entry during posting, and THAT is what the fact must carry (odoo-parity J3).
        stubCosts(new BigDecimal("2.0000"), null);
        stubBelowThreshold();
        stubPostingWithEngineCost(new BigDecimal("2.5000"));
        when(ledgerRepository.calculateOnHandQuantityAtLocation(SKU, LOCATION_ID))
                .thenReturn(10);

        service.createScrap(createRequest(ScrapReasonCode.DAMAGED, null));

        ArgumentCaptor<ScrapPostedV1> factCaptor = ArgumentCaptor.forClass(ScrapPostedV1.class);
        verify(inventoryFactPublisher).recordScrapPosted(factCaptor.capture());
        assertThat(factCaptor.getValue().unitCost()).isEqualByComparingTo("2.50");
        assertThat(factCaptor.getValue().costSource()).isEqualTo("AVERAGE");
    }

    @Test
    @DisplayName("J3: STANDARD-costed SKU labels the fact costSource STANDARD")
    void createScrap_factCarriesEngineCost_standard() {
        stubCosts(new BigDecimal("2.0000"), null);
        stubBelowThreshold();
        stubPostingWithEngineCost(new BigDecimal("6.0000"));
        when(methodResolver.resolve(SKU)).thenReturn(CostingMethod.STANDARD);
        when(ledgerRepository.calculateOnHandQuantityAtLocation(SKU, LOCATION_ID))
                .thenReturn(10);

        service.createScrap(createRequest(ScrapReasonCode.DAMAGED, null));

        ArgumentCaptor<ScrapPostedV1> factCaptor = ArgumentCaptor.forClass(ScrapPostedV1.class);
        verify(inventoryFactPublisher).recordScrapPosted(factCaptor.capture());
        assertThat(factCaptor.getValue().unitCost()).isEqualByComparingTo("6.00");
        assertThat(factCaptor.getValue().costSource()).isEqualTo("STANDARD");
    }

    @Test
    @DisplayName("J3: an uncosted SKU (engine stamped no cost) still emits a null-cost / NONE fact")
    void createScrap_uncostedEngine_emitsNoneCostFact() {
        // The approval snapshot exists (auto-posts), but the engine has no running cost yet, so the
        // stamped SCRAP_OUT cost is null — the fact stays record-and-skip for the D2 consumer.
        stubCosts(new BigDecimal("2.0000"), null);
        stubBelowThreshold();
        stubPostingWithEngineCost(null);
        when(ledgerRepository.calculateOnHandQuantityAtLocation(SKU, LOCATION_ID))
                .thenReturn(10);

        service.createScrap(createRequest(ScrapReasonCode.DAMAGED, null));

        ArgumentCaptor<ScrapPostedV1> factCaptor = ArgumentCaptor.forClass(ScrapPostedV1.class);
        verify(inventoryFactPublisher).recordScrapPosted(factCaptor.capture());
        assertThat(factCaptor.getValue().unitCost()).isNull();
        assertThat(factCaptor.getValue().costSource()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("Above threshold: PENDING_APPROVAL with the evaluated tier, nothing posted")
    void createScrap_aboveThreshold_requiresApproval() {
        stubCosts(new BigDecimal("500.0000"), null);
        when(thresholdEvaluator.evaluateScrapApprovalTier(any(BigDecimal.class)))
                .thenReturn(Optional.of(ApprovalTier.TIER_2_DIRECTOR));

        ScrapResponse response = service.createScrap(createRequest(ScrapReasonCode.RECALLED, null));

        assertThat(response.getStatus()).isEqualTo(ScrapStatus.PENDING_APPROVAL);
        assertThat(response.getRequiredApprovalTier()).isEqualTo(ApprovalTier.TIER_2_DIRECTOR);
        verifyNoInteractions(ledgerPostingService);
    }

    @Test
    @DisplayName("Approve posts the pending scrap; actor from security context")
    void approveScrap_postsPendingScrap() {
        ScrapRecord pending = pendingScrap();
        when(scrapRepository.findById(pending.getScrapId())).thenReturn(Optional.of(pending));
        when(ledgerRepository.calculateOnHandQuantityAtLocation(SKU, LOCATION_ID))
                .thenReturn(10);
        stubPostingSuccess();

        ScrapResponse response = service.approveScrap(pending.getScrapId(), new ApproveScrapRequest());

        assertThat(response.getStatus()).isEqualTo(ScrapStatus.POSTED);
        assertThat(response.getApprovedBy()).isEqualTo(ACTOR);
        verify(ledgerPostingService).post(any(InventoryLedgerEntry.class), eq(false));
    }

    @Test
    @DisplayName("Reject leaves stock untouched")
    void rejectScrap_leavesStockUntouched() {
        ScrapRecord pending = pendingScrap();
        when(scrapRepository.findById(pending.getScrapId())).thenReturn(Optional.of(pending));

        ScrapResponse response =
                service.rejectScrap(pending.getScrapId(), new RejectScrapRequest("Recovered and restocked"));

        assertThat(response.getStatus()).isEqualTo(ScrapStatus.REJECTED);
        assertThat(response.getRejectedBy()).isEqualTo(ACTOR);
        assertThat(response.getRejectionReason()).isEqualTo("Recovered and restocked");
        verifyNoInteractions(ledgerPostingService);
        verify(inventoryFactPublisher, never()).recordScrapPosted(any());
    }

    @Test
    @DisplayName("Approving a non-pending scrap is rejected")
    void approveScrap_nonPending_throws() {
        ScrapRecord posted = pendingScrap();
        posted.setStatus(ScrapStatus.POSTED);
        when(scrapRepository.findById(posted.getScrapId())).thenReturn(Optional.of(posted));

        assertThatThrownBy(() -> service.approveScrap(posted.getScrapId(), new ApproveScrapRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot approve scrap in status");
    }

    @Test
    @DisplayName("Funnel's override-required rejection surfaces as the guided SCRAP_INSUFFICIENT_STOCK 422")
    void approveScrap_insufficientStock_surfacesGuided422() {
        ScrapRecord pending = pendingScrap();
        when(scrapRepository.findById(pending.getScrapId())).thenReturn(Optional.of(pending));
        when(ledgerRepository.calculateOnHandQuantityAtLocation(SKU, LOCATION_ID))
                .thenReturn(1);
        when(ledgerPostingService.post(any(InventoryLedgerEntry.class), anyBoolean()))
                .thenThrow(new NegativeStockPolicyViolationException(
                        NegativeStockPolicyViolationException.OVERRIDE_REQUIRED, "below zero"));

        assertThatThrownBy(() -> service.approveScrap(pending.getScrapId(), new ApproveScrapRequest()))
                .isInstanceOf(ScrapInsufficientStockException.class)
                .hasMessageContaining("cycle count")
                .hasMessageContaining("inventory:adjustment:override");
        verify(inventoryFactPublisher, never()).recordScrapPosted(any());
    }

    @Test
    @DisplayName("Override flag without inventory:adjustment:override is a hard permission failure")
    void approveScrap_overrideWithoutPermission_throws() {
        ScrapRecord pending = pendingScrap();
        when(scrapRepository.findById(pending.getScrapId())).thenReturn(Optional.of(pending));

        ApproveScrapRequest request =
                ApproveScrapRequest.builder().negativeStockOverride(true).build();
        assertThatThrownBy(() -> service.approveScrap(pending.getScrapId(), request))
                .isInstanceOf(InsufficientPermissionException.class)
                .hasMessageContaining("inventory:adjustment:override");
        verifyNoInteractions(ledgerPostingService);
    }

    @Test
    @DisplayName("Override flag with the permission passes the explicit flag to the funnel")
    void approveScrap_overrideWithPermission_passesFlagToFunnel() {
        setUpAuthenticatedActor("inventory:adjustment:override");
        ScrapRecord pending = pendingScrap();
        when(scrapRepository.findById(pending.getScrapId())).thenReturn(Optional.of(pending));
        when(ledgerRepository.calculateOnHandQuantityAtLocation(SKU, LOCATION_ID))
                .thenReturn(1);
        stubPostingSuccess();

        ApproveScrapRequest request =
                ApproveScrapRequest.builder().negativeStockOverride(true).build();
        ScrapResponse response = service.approveScrap(pending.getScrapId(), request);

        assertThat(response.getStatus()).isEqualTo(ScrapStatus.POSTED);
        verify(ledgerPostingService).post(any(InventoryLedgerEntry.class), eq(true));
    }

    @Test
    @DisplayName("shouldReplenish triggers a pick-face evaluation after posting")
    void createScrap_shouldReplenish_triggersEvaluation() {
        stubCosts(new BigDecimal("2.0000"), null);
        stubBelowThreshold();
        stubPostingSuccess();
        when(replenishmentService.evaluatePickFaceForReplenishment(SKU, LOCATION_ID))
                .thenReturn(
                        ReplenishmentTaskResponse.builder().status("NO_ACTION").build());

        CreateScrapRequest request = createRequest(ScrapReasonCode.DAMAGED, null);
        request.setShouldReplenish(true);
        service.createScrap(request);

        verify(replenishmentService).evaluatePickFaceForReplenishment(SKU, LOCATION_ID);
    }

    private CreateScrapRequest createRequest(ScrapReasonCode reasonCode, String notes) {
        return CreateScrapRequest.builder()
                .stockItemId(SKU)
                .quantity(3)
                .locationId(LOCATION_ID)
                .reasonCode(reasonCode)
                .notes(notes)
                .build();
    }

    private ScrapRecord pendingScrap() {
        return ScrapRecord.builder()
                .scrapId(UUID.randomUUID())
                .stockItemId(SKU)
                .quantity(3)
                .locationId(LOCATION_ID)
                .reasonCode(ScrapReasonCode.DAMAGED)
                .unitCostSnapshot(new BigDecimal("500.0000"))
                .costSource(ScrapCostSource.LATEST_RECEIPT)
                .status(ScrapStatus.PENDING_APPROVAL)
                .requiredApprovalTier(ApprovalTier.TIER_1_MANAGER)
                .createdBy("creator-user")
                .build();
    }

    private void stubCosts(BigDecimal receiptCost, BigDecimal anyCost) {
        when(ledgerRepository.findLatestUnitCostByStockItemAndEventType(
                        eq(SKU), eq(InventoryLedgerEventType.GOODS_RECEIPT), any(Pageable.class)))
                .thenReturn(receiptCost == null ? List.of() : List.of(receiptCost));
        if (receiptCost == null) {
            when(ledgerRepository.findLatestUnitCostByStockItem(eq(SKU), any(Pageable.class)))
                    .thenReturn(anyCost == null ? List.of() : List.of(anyCost));
        }
    }

    private void stubBelowThreshold() {
        when(thresholdEvaluator.evaluateScrapApprovalTier(any(BigDecimal.class)))
                .thenReturn(Optional.empty());
    }

    private UUID stubPostingSuccess() {
        UUID ledgerEntryId = UUID.randomUUID();
        when(ledgerPostingService.post(any(InventoryLedgerEntry.class), anyBoolean()))
                .thenAnswer(invocation -> {
                    InventoryLedgerEntry entry = invocation.getArgument(0);
                    entry.setLedgerEntryId(ledgerEntryId);
                    return entry;
                });
        return ledgerEntryId;
    }

    /**
     * Posting stub that simulates the J1 funnel's {@code LedgerCostingService} stamping a
     * method-derived {@code engineCost} on the SCRAP_OUT entry: returns a fresh persisted entry
     * carrying {@code engineCost} (which may differ from the interim snapshot the service set),
     * leaving the captured POST argument untouched so cost provenance is testable.
     */
    private UUID stubPostingWithEngineCost(BigDecimal engineCost) {
        UUID ledgerEntryId = UUID.randomUUID();
        when(ledgerPostingService.post(any(InventoryLedgerEntry.class), anyBoolean()))
                .thenAnswer(invocation -> {
                    InventoryLedgerEntry arg = invocation.getArgument(0);
                    return InventoryLedgerEntry.builder()
                            .ledgerEntryId(ledgerEntryId)
                            .stockItemId(arg.getStockItemId())
                            .eventType(arg.getEventType())
                            .changeInQuantity(arg.getChangeInQuantity())
                            .quantityAfter(arg.getQuantityAfter())
                            .unitCost(engineCost)
                            .locationId(arg.getLocationId())
                            .build();
                });
        return ledgerEntryId;
    }

    private void setUpAuthenticatedActor(String... authorities) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(ACTOR, "password", authorities);
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USER_ID,
                "actor-person-id-001",
                GatewaySecurityConstants.DETAIL_USERNAME,
                ACTOR));
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
