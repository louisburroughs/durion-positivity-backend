package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
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

/** ADR-0067 A8 (#2662, AC 20): the three-line cash application entry. */
class GLPostingServiceImplCashRoundingTest {

    private static final UUID PA = UUID.fromString("00000000-0000-0000-0000-00000000aa01");
    private static final UUID CASH = UUID.fromString("00000000-0000-0000-0000-000000001090");
    private static final UUID AR = UUID.fromString("00000000-0000-0000-0000-000000001200");
    private static final UUID ROUNDING = UUID.fromString("00000000-0000-0000-0000-000000006050");
    private static final LocalDateTime DATE = LocalDateTime.of(2026, 10, 17, 12, 0);

    private JournalEntryService journalEntryService;
    private GLPostingServiceImpl service;

    @BeforeEach
    void setUp() {
        journalEntryService = mock(JournalEntryService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);
        service = new GLPostingServiceImpl(clock, TestZoneResolvers.utc(clock), journalEntryService);
        UUID entryId = UUID.fromString("00000000-0000-0000-0000-00000000e001");
        JournalEntryResponse created =
                JournalEntryResponse.builder().journalEntryId(entryId).build();
        when(journalEntryService.createJournalEntry(any())).thenReturn(created);
        when(journalEntryService.postJournalEntry(eq(entryId), any())).thenReturn(created);
    }

    private JournalEntryCreateRequest capture() {
        ArgumentCaptor<JournalEntryCreateRequest> captor = ArgumentCaptor.forClass(JournalEntryCreateRequest.class);
        verify(journalEntryService).createJournalEntry(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("AC 20: 10.00 cash on 9.98 due posts Dr 1090 10.00 / Cr 1200 9.98 / Cr 6050 0.02")
    void roundedUp_creditsCashRounding() {
        service.postPaymentApplication(
                PA, CASH, AR, new BigDecimal("9.98"), ROUNDING, new BigDecimal("0.02"), DATE, "AR cash receipt");

        JournalEntryCreateRequest request = capture();
        assertThat(request.getLines()).hasSize(3);
        assertThat(request.getLines().get(0).getGlAccountId()).isEqualTo(CASH);
        assertThat(request.getLines().get(0).getDebitAmount()).isEqualByComparingTo("10.00");
        assertThat(request.getLines().get(1).getGlAccountId()).isEqualTo(AR);
        assertThat(request.getLines().get(1).getCreditAmount()).isEqualByComparingTo("9.98");
        assertThat(request.getLines().get(2).getGlAccountId()).isEqualTo(ROUNDING);
        assertThat(request.getLines().get(2).getCreditAmount()).isEqualByComparingTo("0.02");
        assertThat(request.getLines().get(2).getDebitAmount()).isZero();
        assertThat(request.getSourceEventId()).isEqualTo(PA);
    }

    @Test
    @DisplayName("10.00 cash on 10.02 due posts Dr 1090 10.00 / Cr 1200 10.02 / Dr 6050 0.02")
    void roundedDown_debitsCashRounding() {
        service.postPaymentApplication(
                PA, CASH, AR, new BigDecimal("10.02"), ROUNDING, new BigDecimal("-0.02"), DATE, "AR cash receipt");

        JournalEntryCreateRequest request = capture();
        assertThat(request.getLines().get(0).getDebitAmount()).isEqualByComparingTo("10.00");
        assertThat(request.getLines().get(1).getCreditAmount()).isEqualByComparingTo("10.02");
        assertThat(request.getLines().get(2).getDebitAmount()).isEqualByComparingTo("0.02");
        assertThat(request.getLines().get(2).getCreditAmount()).isZero();
    }

    @Test
    void noRoundingPostsTheTwoLines() {
        service.postPaymentApplication(PA, CASH, AR, new BigDecimal("9.98"), null, null, DATE, "AR cash receipt");

        JournalEntryCreateRequest request = capture();
        assertThat(request.getLines()).hasSize(2);
        assertThat(request.getLines().get(0).getDebitAmount()).isEqualByComparingTo("9.98");
    }

    @Test
    void aRoundingWithoutItsAccountIsRefused() {
        assertThatThrownBy(() -> service.postPaymentApplication(
                        PA, CASH, AR, new BigDecimal("9.98"), null, new BigDecimal("0.02"), DATE, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a credit issuance that is the tender's first cash-receipt entry carries the rounding")
    void creditIssuance_carriesTheRounding() {
        UUID credit = UUID.fromString("00000000-0000-0000-0000-00000000cc01");
        UUID liability = UUID.fromString("00000000-0000-0000-0000-000000002300");
        service.postCustomerCreditIssuance(
                PA, credit, CASH, liability, new BigDecimal("9.98"), ROUNDING, new BigDecimal("0.02"), DATE, "x", null);

        JournalEntryCreateRequest request = capture();
        assertThat(request.getLines()).hasSize(3);
        assertThat(request.getLines().get(0).getDebitAmount()).isEqualByComparingTo("10.00");
        assertThat(request.getLines().get(1).getGlAccountId()).isEqualTo(liability);
        assertThat(request.getLines().get(1).getCreditAmount()).isEqualByComparingTo("9.98");
        assertThat(request.getLines().get(2).getGlAccountId()).isEqualTo(ROUNDING);
        assertThat(request.getLines().get(2).getCreditAmount()).isEqualByComparingTo("0.02");
    }
}
