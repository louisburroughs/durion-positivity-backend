package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.service.GLMappingResolver;
import com.positivity.accounting.service.GLPostingService;
import com.positivity.accounting.service.IdempotencyService;
import com.positivity.domainevents.inventory.ScrapPostedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link InventoryShrinkagePostingService} (odoo-parity D2, issue #1043): account
 * resolution through the {@code INVENTORY_SHRINKAGE} posting category, {@code quantity x unitCost}
 * amount, business-time transaction date, and scrapId-keyed posting idempotency.
 */
class InventoryShrinkagePostingServiceTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID SCRAP_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID SHRINKAGE_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID INVENTORY_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID JOURNAL_ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-21T09:15:00Z");

    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
    private final GLMappingResolver glMappingResolver = mock(GLMappingResolver.class);
    private final GLPostingService glPostingService = mock(GLPostingService.class);

    private InventoryShrinkagePostingService service;

    @BeforeEach
    void setUp() {
        service = new InventoryShrinkagePostingService(
                TEST_CLOCK, idempotencyService, glMappingResolver, glPostingService);
    }

    private ScrapPostedV1 fact(BigDecimal unitCost) {
        return new ScrapPostedV1(
                SCRAP_ID, "BRAKE-PAD-22", null, null, 3, "EXPIRED", unitCost, "LATEST_RECEIPT", null, OCCURRED_AT);
    }

    @Test
    @DisplayName("Posts Dr shrinkage / Cr inventory for quantity x unitCost at business time")
    void postsBalancedShrinkageEntry() {
        LocalDateTime expectedDate = LocalDateTime.ofInstant(OCCURRED_AT, ZoneOffset.UTC);
        when(idempotencyService.isKeyProcessed("INVENTORY_SHRINKAGE_GL_POSTING:" + SCRAP_ID))
                .thenReturn(false);
        when(glMappingResolver.resolveGLAccount("INVENTORY_SHRINKAGE", "SHRINKAGE_EXPENSE", expectedDate))
                .thenReturn(SHRINKAGE_ACCOUNT);
        when(glMappingResolver.resolveGLAccount("INVENTORY_SHRINKAGE", "INVENTORY_ASSET", expectedDate))
                .thenReturn(INVENTORY_ACCOUNT);
        JournalEntry posted = new JournalEntry();
        posted.setJournalEntryId(JOURNAL_ENTRY_ID);
        when(glPostingService.postInventoryShrinkage(any(), any(), any(), any(), any(), any(), anyString(), any()))
                .thenReturn(posted);

        service.postShrinkage(fact(new BigDecimal("12.50")));

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(glPostingService)
                .postInventoryShrinkage(
                        eq(InventoryShrinkagePostingService.toSourceEventId(SCRAP_ID)),
                        eq(SCRAP_ID),
                        eq(SHRINKAGE_ACCOUNT),
                        eq(INVENTORY_ACCOUNT),
                        amount.capture(),
                        eq(expectedDate),
                        description.capture(),
                        eq(null));
        assertThat(amount.getValue()).isEqualByComparingTo(new BigDecimal("37.50"));
        // The reason code rides into the JE memo (spec D4 scope 4).
        assertThat(description.getValue()).contains("EXPIRED").contains(SCRAP_ID.toString());
        verify(idempotencyService).registerKey("INVENTORY_SHRINKAGE_GL_POSTING:" + SCRAP_ID, JOURNAL_ENTRY_ID);
    }

    @Test
    @DisplayName("Already-processed posting key is a no-op — exactly once per scrapId")
    void replayedScrapIdIsNoOp() {
        when(idempotencyService.isKeyProcessed("INVENTORY_SHRINKAGE_GL_POSTING:" + SCRAP_ID))
                .thenReturn(true);

        service.postShrinkage(fact(new BigDecimal("12.50")));

        verify(glPostingService, never())
                .postInventoryShrinkage(any(), any(), any(), any(), any(), any(), anyString(), any());
        verify(idempotencyService, never()).registerKey(anyString(), any());
    }

    @Test
    @DisplayName("Source event id derivation is deterministic and namespaced")
    void sourceEventIdIsDeterministic() {
        assertThat(InventoryShrinkagePostingService.toSourceEventId(SCRAP_ID))
                .isEqualTo(InventoryShrinkagePostingService.toSourceEventId(SCRAP_ID))
                .isNotEqualTo(SCRAP_ID);
    }
}
