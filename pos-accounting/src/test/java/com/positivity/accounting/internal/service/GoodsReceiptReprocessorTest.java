package com.positivity.accounting.internal.service;

import static com.positivity.accounting.internal.service.GoodsReceiptAccrualPostingServiceTest.fact;
import static com.positivity.accounting.internal.service.GoodsReceiptAccrualPostingServiceTest.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.IdempotencyOutcome;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reprocessing a held goods receipt re-runs its own assessment on the stored payload (CAP:550 S41, #2602; Accounting
 * ruling 4): the currency first, then the line rules, then the posting under the receipt key.
 */
@DisplayName("GoodsReceiptReprocessor (S41 #2602, ruling 4)")
class GoodsReceiptReprocessorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GoodsReceiptAccrualPostingService real = new GoodsReceiptAccrualPostingService(
            TestZoneResolvers.utc(Clock.fixed(Instant.parse("2026-10-08T14:30:00Z"), ZoneOffset.UTC)),
            mock(IdempotencyService.class),
            mock(GLMappingResolver.class),
            mock(JournalEntryService.class),
            mock(JournalEntryRepository.class),
            new LedgerCurrency("USD"));
    private final GoodsReceiptAccrualPostingService postingService = mock(GoodsReceiptAccrualPostingService.class);
    private final JournalEntryRepository entries = mock(JournalEntryRepository.class);
    private GoodsReceiptReprocessor reprocessor;

    @BeforeEach
    void setUp() {
        // The assessment is the real one; only the posting is stubbed.
        when(postingService.assess(any())).thenAnswer(invocation -> real.assess(invocation.getArgument(0)));
        reprocessor = new GoodsReceiptReprocessor(
                postingService, entries, objectMapper, mock(PlatformTransactionManager.class));
    }

    @Test
    @DisplayName("a currency-less receipt stays SUSPENDED / CURRENCY_NOT_SUPPORTED: null is no currency, never at par")
    void currencyLessStaysHeld() {
        GoodsReceiptReprocessor.Result result =
                reprocessor.reprocess(stored(fact(null, 40_000L, line("4", 40_000L, 40_000L, "AVERAGE"))));

        assertThat(result.status()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(result.reason()).isEqualTo("CURRENCY_NOT_SUPPORTED");
        verify(postingService, never()).postAccrual(any());
    }

    @Test
    @DisplayName("a malformed receipt stays SUSPENDED / VALIDATION_ERROR with its detail, never NO_RULE_VERSION")
    void malformedStaysHeld() {
        GoodsReceiptReprocessor.Result result =
                reprocessor.reprocess(stored(fact("USD", 40_001L, line("4", 40_000L, 40_000L, "AVERAGE"))));

        assertThat(result.status()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(result.reason()).isEqualTo("VALIDATION_ERROR");
        assertThat(result.detail()).contains("not totalAccruedAmountMinor 40001");
        verify(postingService, never()).postAccrual(any());
    }

    @Test
    @DisplayName("a receipt that now passes posts under its key: PROCESSED / NEW with the entry")
    void validPosts() {
        UUID entry = UUID.randomUUID();
        when(postingService.postAccrual(any())).thenReturn(entry);

        GoodsReceiptReprocessor.Result result =
                reprocessor.reprocess(stored(fact("USD", 40_000L, line("4", 40_000L, 40_000L, "AVERAGE"))));

        assertThat(result.status()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(result.reason()).isNull();
        assertThat(result.journalEntryId()).isEqualTo(entry);
        assertThat(result.idempotencyOutcome()).isEqualTo(IdempotencyOutcome.NEW);
    }

    @Test
    @DisplayName("a receipt whose key already posted posts nothing: PROCESSED / DUPLICATE_IGNORED, linked to it")
    void alreadyPostedIsDuplicate() {
        JournalEntry earlier = new JournalEntry();
        earlier.setJournalEntryId(UUID.randomUUID());
        when(postingService.postAccrual(any())).thenReturn(null);
        when(entries.findBySourceEvent(eq(GoodsReceiptAccrualPostingService.toSourceEventId(
                        GoodsReceiptAccrualPostingServiceTest.RECEIPT))))
                .thenReturn(List.of(earlier));

        GoodsReceiptReprocessor.Result result =
                reprocessor.reprocess(stored(fact("USD", 40_000L, line("4", 40_000L, 40_000L, "AVERAGE"))));

        assertThat(result.status()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(result.idempotencyOutcome()).isEqualTo(IdempotencyOutcome.DUPLICATE_IGNORED);
        assertThat(result.journalEntryId()).isEqualTo(earlier.getJournalEntryId());
    }

    @Test
    @DisplayName("a closed period is SUSPENDED / PERIOD_CLOSED; a missing mapping SUSPENDED / UNMAPPED_EVENT_TYPE")
    void refusalsAreLabelled() {
        GoodsReceiptRecordedV1 fact = fact("USD", 40_000L, line("4", 40_000L, 40_000L, "AVERAGE"));
        when(postingService.postAccrual(any()))
                .thenThrow(new AccountingPeriodClosedException("2026-10", "period 2026-10 is CLOSED"));
        GoodsReceiptReprocessor.Result closed = reprocessor.reprocess(stored(fact));
        assertThat(closed.reason()).isEqualTo("PERIOD_CLOSED");
        assertThat(closed.detail()).contains("reprocess after the period is reopened");

        // A hard lock is never reopened: the engine's wording, permanent, never "reprocess after it is open".
        org.mockito.Mockito.doThrow(
                        new com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException(
                                java.time.LocalDate.of(2026, 10, 31),
                                "2026-10-08 is on or before the hard lock 2026-10-31"))
                .when(postingService)
                .postAccrual(any());
        GoodsReceiptReprocessor.Result locked = reprocessor.reprocess(stored(fact));
        assertThat(locked.status()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(locked.reason()).isEqualTo("PERIOD_CLOSED");
        assertThat(locked.detail())
                .contains("permanently blocked and cannot be reprocessed")
                .doesNotContain("reprocess after");

        org.mockito.Mockito.doThrow(new GLMappingNotConfiguredException("no GOODS_RECEIPT map"))
                .when(postingService)
                .postAccrual(any());
        GoodsReceiptReprocessor.Result unmapped = reprocessor.reprocess(stored(fact));
        assertThat(unmapped.status()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(unmapped.reason()).isEqualTo("UNMAPPED_EVENT_TYPE");
    }

    @Test
    @DisplayName("an uncosted receipt is SKIPPED / UNCOSTED_FACT; an unreadable payload stays VALIDATION_ERROR")
    void uncostedAndUnreadable() {
        assertThat(reprocessor
                        .reprocess(stored(fact("USD", 0L, line("4", 0L, null, "NONE"))))
                        .status())
                .isEqualTo(AccountingEventStatus.SKIPPED);
        assertThat(reprocessor.reprocess(Map.of("receiptId", "not-a-uuid")).reason())
                .isEqualTo("VALIDATION_ERROR");
    }

    private Map<String, Object> stored(GoodsReceiptRecordedV1 fact) {
        return objectMapper.convertValue(fact, new TypeReference<Map<String, Object>>() {});
    }
}
