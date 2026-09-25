package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.domainevents.inventory.ProductValueChangedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Sign routing, amount, date and idempotency of {@link InventoryRevaluationPostingService} (#2193). */
class InventoryRevaluationPostingServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID REVALUATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID INVENTORY = UUID.fromString("00000000-0000-0000-0000-000000001300");
    private static final UUID COGS = UUID.fromString("00000000-0000-0000-0000-000000005000");
    private static final UUID JOURNAL_ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-00000000000e");
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-21T09:15:00Z");
    private static final String KEY = "INVENTORY_REVALUATION_GL_POSTING:" + REVALUATION_ID;

    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
    private final GLMappingResolver glMappingResolver = mock(GLMappingResolver.class);
    private final GLPostingService glPostingService = mock(GLPostingService.class);
    private final JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);

    private InventoryRevaluationPostingService service;

    @BeforeEach
    void setUp() {
        service = new InventoryRevaluationPostingService(
                TEST_CLOCK, idempotencyService, glMappingResolver, glPostingService, journalEntryRepository);
        LocalDateTime date = LocalDateTime.ofInstant(OCCURRED_AT, ZoneOffset.UTC);
        when(glMappingResolver.resolveGLAccount("INVENTORY_REVALUATION", "INVENTORY_ASSET", date))
                .thenReturn(INVENTORY);
        when(glMappingResolver.resolveGLAccount("INVENTORY_REVALUATION", "REVALUATION_OFFSET", date))
                .thenReturn(COGS);
        when(glPostingService.postInventoryRevaluation(any(), any(), any(), any(), any(), any(), anyString(), any()))
                .thenReturn(JOURNAL_ENTRY_ID);
    }

    private static ProductValueChangedV1 fact(String previousUnitCost, String newUnitCost, String onHandQuantity) {
        BigDecimal previous = previousUnitCost == null ? BigDecimal.ZERO : new BigDecimal(previousUnitCost);
        BigDecimal delta = new BigDecimal(newUnitCost).subtract(previous).multiply(new BigDecimal(onHandQuantity));
        return new ProductValueChangedV1(
                REVALUATION_ID,
                "BRAKE-PAD-22",
                "AVERAGE",
                previousUnitCost == null ? null : new BigDecimal(previousUnitCost),
                new BigDecimal(newUnitCost),
                new BigDecimal(onHandQuantity),
                delta,
                "Supplier price correction",
                "jdoe",
                OCCURRED_AT);
    }

    @Test
    @DisplayName("Write-up posts Dr INVENTORY_ASSET / Cr REVALUATION_OFFSET for abs(totalValueDelta) at occurredAt")
    void writeUpRoutesToInventory() {
        UUID posted = service.postRevaluation(fact("5.00", "7.25", "4"));

        assertThat(posted).isEqualTo(JOURNAL_ENTRY_ID);
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(glPostingService)
                .postInventoryRevaluation(
                        eq(InventoryRevaluationPostingService.toSourceEventId(REVALUATION_ID)),
                        eq(REVALUATION_ID),
                        eq(INVENTORY),
                        eq(COGS),
                        eq(new BigDecimal("9.00")),
                        eq(LocalDateTime.ofInstant(OCCURRED_AT, ZoneOffset.UTC)),
                        description.capture(),
                        eq(null));
        assertThat(description.getValue())
                .contains("write-up", "BRAKE-PAD-22", "5.00", "7.25", REVALUATION_ID.toString());
        verify(idempotencyService).registerKey(KEY, JOURNAL_ENTRY_ID);
    }

    @Test
    @DisplayName("Write-down posts Dr REVALUATION_OFFSET / Cr INVENTORY_ASSET")
    void writeDownRoutesToOffset() {
        service.postRevaluation(fact("10.00", "6.00", "2"));

        verify(glPostingService)
                .postInventoryRevaluation(
                        any(), any(), eq(COGS), eq(INVENTORY), eq(new BigDecimal("8.00")), any(), anyString(), any());
    }

    @Test
    @DisplayName("A zero value delta posts nothing and returns null")
    void zeroDeltaPostsNothing() {
        assertThat(service.postRevaluation(fact("5.00", "5.00", "4"))).isNull();

        verifyNoInteractions(glPostingService);
        verify(idempotencyService, never()).registerKey(anyString(), any());
    }

    @Test
    @DisplayName("A registered posting key posts nothing and returns null")
    void registeredKeyIsDuplicate() {
        when(idempotencyService.isKeyProcessed(KEY)).thenReturn(true);

        assertThat(service.postRevaluation(fact("5.00", "7.25", "4"))).isNull();

        verifyNoInteractions(glPostingService);
        verify(idempotencyService, never()).registerKey(anyString(), any());
    }

    @Test
    @DisplayName("An existing entry for the sourceEventId (posting key expired) is also a duplicate")
    void existingEntryIsDuplicateAfterKeyExpiry() {
        when(journalEntryRepository.findBySourceEvent(
                        InventoryRevaluationPostingService.toSourceEventId(REVALUATION_ID)))
                .thenReturn(List.of(new JournalEntry()));

        assertThat(service.postRevaluation(fact("5.00", "7.25", "4"))).isNull();

        verifyNoInteractions(glPostingService);
    }

    @Test
    @DisplayName("The revaluation id namespaces the key and the source event id")
    void revaluationIdNamespacesKeyAndSourceEvent() {
        assertThat(InventoryRevaluationPostingService.idempotencyKey(REVALUATION_ID))
                .isEqualTo("INVENTORY_REVALUATION_GL_POSTING:" + REVALUATION_ID);
        assertThat(InventoryRevaluationPostingService.toSourceEventId(REVALUATION_ID))
                .isNotEqualTo(InventoryAdjustmentPostingService.toSourceEventId("CYCLE_COUNT", REVALUATION_ID));
    }
}
