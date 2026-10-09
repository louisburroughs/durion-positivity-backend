package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link GLPostingServiceImpl#postMirror} (CAP:550 S32d, ADR-0047; review of #2664 B1): every credit-memo void and a
 * typed invoice-revenue reversal undo an entry by mirroring its lines, so each line's debit and credit must swap,
 * the mirror must balance, and it must carry the caller's source type and id.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GLPostingService.postMirror")
class GLPostingMirrorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime TXN = LocalDateTime.of(2026, 10, 8, 9, 0);

    @Mock
    private JournalEntryService journalEntryService;

    private GLPostingServiceImpl service;
    private final UUID sourceEventId = UUID.randomUUID();
    private final UUID ar = UUID.randomUUID();
    private final UUID revenue = UUID.randomUUID();
    private final UUID gst = UUID.randomUUID();
    private final UUID pst = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GLPostingServiceImpl(CLOCK, TestZoneResolvers.utc(CLOCK), journalEntryService);
    }

    @Test
    @DisplayName("each line's debit and credit swap, the mirror balances, and it carries the source type and id")
    void mirrorSwapsEveryLine() {
        UUID entryId = UUID.randomUUID();
        ArgumentCaptor<JournalEntryCreateRequest> captor = ArgumentCaptor.forClass(JournalEntryCreateRequest.class);
        when(journalEntryService.createJournalEntry(captor.capture()))
                .thenReturn(
                        JournalEntryResponse.builder().journalEntryId(entryId).build());
        when(journalEntryService.postJournalEntry(any(UUID.class), any()))
                .thenReturn(
                        JournalEntryResponse.builder().journalEntryId(entryId).build());
        // The original: Dr AR 1120.00 / Cr revenue 1000.00 / Cr GST 50.00 / Cr PST 70.00.
        List<GLPostingService.PostedLine> original = List.of(
                new GLPostingService.PostedLine(
                        ar, new BigDecimal("1120.00"), BigDecimal.ZERO, "AR", Map.of("locationId", "LOC-1")),
                new GLPostingService.PostedLine(revenue, BigDecimal.ZERO, new BigDecimal("1000.00"), "Revenue", null),
                new GLPostingService.PostedLine(gst, BigDecimal.ZERO, new BigDecimal("50.00"), "GST", null),
                new GLPostingService.PostedLine(pst, BigDecimal.ZERO, new BigDecimal("70.00"), null, null));

        UUID posted = service.postMirror(
                JournalEntrySourceTypes.CREDIT_MEMO_VOID, sourceEventId, original, TXN, "Void Credit Memo");

        assertThat(posted).isEqualTo(entryId);
        JournalEntryCreateRequest request = captor.getValue();
        assertThat(request.getSourceEventType()).isEqualTo(JournalEntrySourceTypes.CREDIT_MEMO_VOID);
        assertThat(request.getSourceEventId()).isEqualTo(sourceEventId);
        assertThat(request.getTransactionDate()).isEqualTo(TXN);
        assertThat(request.getLines())
                .extracting(
                        JournalEntryCreateRequest.JournalEntryLineRequest::getGlAccountId,
                        line -> line.getDebitAmount().stripTrailingZeros(),
                        line -> line.getCreditAmount().stripTrailingZeros())
                .containsExactly(
                        tuple(ar, BigDecimal.ZERO, new BigDecimal("1120.00").stripTrailingZeros()),
                        tuple(revenue, new BigDecimal("1000.00").stripTrailingZeros(), BigDecimal.ZERO),
                        tuple(gst, new BigDecimal("50.00").stripTrailingZeros(), BigDecimal.ZERO),
                        tuple(pst, new BigDecimal("70.00").stripTrailingZeros(), BigDecimal.ZERO));
        BigDecimal debits = request.getLines().stream()
                .map(JournalEntryCreateRequest.JournalEntryLineRequest::getDebitAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = request.getLines().stream()
                .map(JournalEntryCreateRequest.JournalEntryLineRequest::getCreditAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).isEqualByComparingTo(credits).isEqualByComparingTo("1120.00");
        assertThat(request.getLines().getFirst().getDimensions()).containsEntry("locationId", "LOC-1");
        assertThat(request.getLines().getFirst().getDescription()).isEqualTo("Reversal - AR");
        assertThat(request.getLines().getLast().getDescription()).isEqualTo("Reversal");
    }

    @Test
    @DisplayName("an entry without lines cannot be mirrored, and nothing is posted")
    void emptyMirrorIsRefused() {
        assertThatThrownBy(() -> service.postMirror(
                        JournalEntrySourceTypes.CREDIT_MEMO_VOID, sourceEventId, List.of(), TXN, "Void"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(journalEntryService);
    }
}
