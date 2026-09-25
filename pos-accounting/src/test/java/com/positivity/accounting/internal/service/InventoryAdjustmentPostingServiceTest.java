package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
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
import com.positivity.domainevents.inventory.InventoryAdjustedV1;
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

/** Sign routing, amount, date and idempotency of {@link InventoryAdjustmentPostingService} (#2191). */
class InventoryAdjustmentPostingServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID ADJUSTMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SHRINKAGE = UUID.fromString("00000000-0000-0000-0000-000000005100");
    private static final UUID INVENTORY = UUID.fromString("00000000-0000-0000-0000-000000001300");
    private static final UUID JOURNAL_ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-00000000000e");
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-21T09:15:00Z");
    private static final String KEY = "INVENTORY_ADJUSTMENT_GL_POSTING:CYCLE_COUNT:" + ADJUSTMENT_ID;

    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
    private final GLMappingResolver glMappingResolver = mock(GLMappingResolver.class);
    private final GLPostingService glPostingService = mock(GLPostingService.class);
    private final JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);

    private InventoryAdjustmentPostingService service;

    @BeforeEach
    void setUp() {
        service = new InventoryAdjustmentPostingService(
                TEST_CLOCK, idempotencyService, glMappingResolver, glPostingService, journalEntryRepository);
        LocalDateTime date = LocalDateTime.ofInstant(OCCURRED_AT, ZoneOffset.UTC);
        when(glMappingResolver.resolveGLAccount("INVENTORY_ADJUSTMENT", "ADJUSTMENT_LOSS", date))
                .thenReturn(SHRINKAGE);
        when(glMappingResolver.resolveGLAccount("INVENTORY_ADJUSTMENT", "ADJUSTMENT_GAIN", date))
                .thenReturn(SHRINKAGE);
        when(glMappingResolver.resolveGLAccount("INVENTORY_ADJUSTMENT", "INVENTORY_ASSET", date))
                .thenReturn(INVENTORY);
        when(glPostingService.postInventoryAdjustment(any(), any(), any(), any(), any(), any(), anyString(), any()))
                .thenReturn(JOURNAL_ENTRY_ID);
    }

    private static InventoryAdjustedV1 fact(String quantityDelta, String unitCost) {
        return new InventoryAdjustedV1(
                ADJUSTMENT_ID,
                InventoryAdjustedV1.KIND_CYCLE_COUNT,
                "COUNT_VARIANCE_OUT",
                UUID.fromString("00000000-0000-0000-0000-0000000000a2"),
                "BRAKE-PAD-22",
                null,
                null,
                "COUNT_ERROR",
                new BigDecimal(quantityDelta),
                unitCost == null ? null : new BigDecimal(unitCost),
                "AVERAGE",
                OCCURRED_AT);
    }

    @Test
    @DisplayName("Loss posts Dr ADJUSTMENT_LOSS / Cr INVENTORY_ASSET for abs(delta) x unitCost at occurredAt")
    void lossRoutesToShrinkage() {
        UUID posted = service.postAdjustment(fact("-4", "7.25"));

        assertThat(posted).isEqualTo(JOURNAL_ENTRY_ID);
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(glPostingService)
                .postInventoryAdjustment(
                        eq(InventoryAdjustmentPostingService.toSourceEventId("CYCLE_COUNT", ADJUSTMENT_ID)),
                        eq(ADJUSTMENT_ID),
                        eq(SHRINKAGE),
                        eq(INVENTORY),
                        eq(new BigDecimal("29.00")),
                        eq(LocalDateTime.ofInstant(OCCURRED_AT, ZoneOffset.UTC)),
                        description.capture(),
                        eq(null));
        assertThat(description.getValue())
                .contains("COUNT_ERROR", "BRAKE-PAD-22", "-4", "7.25", ADJUSTMENT_ID.toString());
        verify(idempotencyService).registerKey(KEY, JOURNAL_ENTRY_ID);
    }

    @Test
    @DisplayName("Gain posts Dr INVENTORY_ASSET / Cr ADJUSTMENT_GAIN")
    void gainRoutesToInventory() {
        service.postAdjustment(fact("2.5", "10.00"));

        verify(glPostingService)
                .postInventoryAdjustment(
                        any(),
                        any(),
                        eq(INVENTORY),
                        eq(SHRINKAGE),
                        eq(new BigDecimal("25.000")),
                        any(),
                        anyString(),
                        any());
    }

    @Test
    @DisplayName("A registered posting key posts nothing and returns null")
    void registeredKeyIsDuplicate() {
        when(idempotencyService.isKeyProcessed(KEY)).thenReturn(true);

        assertThat(service.postAdjustment(fact("-1", "1.00"))).isNull();

        verifyNoInteractions(glPostingService);
        verify(idempotencyService, never()).registerKey(anyString(), any());
    }

    @Test
    @DisplayName("An existing entry for the sourceEventId (posting key expired) is also a duplicate")
    void existingEntryIsDuplicateAfterKeyExpiry() {
        when(journalEntryRepository.findBySourceEvent(
                        InventoryAdjustmentPostingService.toSourceEventId("CYCLE_COUNT", ADJUSTMENT_ID)))
                .thenReturn(List.of(new JournalEntry()));

        assertThat(service.postAdjustment(fact("-1", "1.00"))).isNull();

        verifyNoInteractions(glPostingService);
    }

    @Test
    @DisplayName("Kind is part of the key and the source event id")
    void kindNamespacesKeyAndSourceEvent() {
        assertThat(InventoryAdjustmentPostingService.idempotencyKey("MANUAL_ADJUSTMENT", ADJUSTMENT_ID))
                .isEqualTo("INVENTORY_ADJUSTMENT_GL_POSTING:MANUAL_ADJUSTMENT:" + ADJUSTMENT_ID);
        assertThat(InventoryAdjustmentPostingService.toSourceEventId("MANUAL_ADJUSTMENT", ADJUSTMENT_ID))
                .isNotEqualTo(InventoryAdjustmentPostingService.toSourceEventId("CYCLE_COUNT", ADJUSTMENT_ID));
    }

    @Test
    @DisplayName("An uncosted fact reaching posting is an internal routing defect")
    void uncostedFactRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.postAdjustment(fact("-1", null)));
    }
}
