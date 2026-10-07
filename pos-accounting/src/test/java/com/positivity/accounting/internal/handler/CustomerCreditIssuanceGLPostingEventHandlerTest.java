package com.positivity.accounting.internal.handler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.CustomerCreditIssuanceGLPostingEvent;
import com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException;
import com.positivity.accounting.internal.service.GLMappingResolver;
import com.positivity.accounting.internal.service.GLPostingService;
import com.positivity.accounting.internal.service.IdempotencyService;
import com.positivity.accounting.internal.service.TestZoneResolvers;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The customer-credit issuance entry's date in the tenant's accounting calendar (#2558). */
@DisplayName("CustomerCreditIssuanceGLPostingEventHandler: accounting-calendar zone (#2558)")
class CustomerCreditIssuanceGLPostingEventHandlerTest {

    private static final Clock UTC_CLOCK = Clock.fixed(TestZoneResolvers.JAN_31_2330_CHICAGO, ZoneOffset.UTC);
    private static final UUID CREDIT_ID = UUID.fromString("00000000-0000-7000-8000-000000002558");
    private static final UUID JOURNAL_ENTRY_ID = UUID.fromString("00000000-0000-7000-8000-00000000e558");

    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
    private final GLMappingResolver glMappingResolver = mock(GLMappingResolver.class);
    private final GLPostingService glPostingService = mock(GLPostingService.class);

    private static CustomerCreditIssuanceGLPostingEvent event() {
        return CustomerCreditIssuanceGLPostingEvent.builder()
                .eventId(UUID.fromString("00000000-0000-7000-8000-00000000a558"))
                .applicationRequestId("APPLY:PAYMENT_SETTLED:2558")
                .creditId(CREDIT_ID)
                .paymentId(UUID.fromString("00000000-0000-7000-8000-00000000b558"))
                .customerId(UUID.fromString("00000000-0000-7000-8000-00000000c558"))
                .currency("USD")
                .creditAmount(new BigDecimal("5.00"))
                .applicationTimestamp(TestZoneResolvers.JAN_31_2330_CHICAGO)
                .build();
    }

    @Test
    @DisplayName("an excess credited at 2026-01-31T23:30-06:00 is issued on 2026-01-31 in a Chicago calendar, clock"
            + " in UTC")
    void chicagoCalendar_lastEveningOfJanuaryIssuesInJanuary() {
        CustomerCreditIssuanceGLPostingEventHandler handler = new CustomerCreditIssuanceGLPostingEventHandler(
                TestZoneResolvers.fixed(TestZoneResolvers.CHICAGO, UTC_CLOCK),
                idempotencyService,
                glMappingResolver,
                glPostingService);
        LocalDateTime january31 = LocalDateTime.of(2026, 1, 31, 23, 30);
        when(glMappingResolver.resolveGLAccount(anyString(), anyString(), eq(january31)))
                .thenReturn(UUID.fromString("00000000-0000-7000-8000-000000001090"));
        when(glPostingService.postCustomerCreditIssuance(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(JOURNAL_ENTRY_ID);

        handler.onCustomerCreditIssuanceGLPosting(event());

        verify(glPostingService)
                .postCustomerCreditIssuance(
                        any(), eq(CREDIT_ID), any(), any(), eq(new BigDecimal("5.00")), eq(january31), any(), any());
    }

    @Test
    @DisplayName("without an accounting time zone nothing is issued; the work item fails unwrapped and retries")
    void unsetZone_failsClosedForRetry() {
        CustomerCreditIssuanceGLPostingEventHandler handler = new CustomerCreditIssuanceGLPostingEventHandler(
                TestZoneResolvers.unset(UTC_CLOCK), idempotencyService, glMappingResolver, glPostingService);

        assertThatThrownBy(() -> handler.onCustomerCreditIssuanceGLPosting(event()))
                .isInstanceOf(AccountingTimeZoneUnsetException.class);

        verifyNoInteractions(glPostingService);
        verify(idempotencyService, never()).registerKey(anyString(), any());
    }
}
