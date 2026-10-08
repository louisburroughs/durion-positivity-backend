package com.positivity.accounting.internal.handler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.APPaymentGLPostingEvent;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.service.APPaymentFailurePersistenceService;
import com.positivity.accounting.internal.service.APPaymentPostingService;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

/**
 * Outbox delivery of an AP payment (CAP:550 S42, #2603; ruling 4): a refusal leaves the payment GL_POST_FAILED and the
 * outbox row completes; anything else propagates for the outbox to retry.
 */
@DisplayName("AP payment outbox delivery: refusals versus transient failures (S42, #2603)")
class APPaymentGLPostingEventHandlerTest {

    private static final UUID PAYMENT_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f7001");

    private final APPaymentPostingService postingService = mock(APPaymentPostingService.class);
    private final APPaymentFailurePersistenceService failures = mock(APPaymentFailurePersistenceService.class);
    private final APPaymentGLPostingEventHandler handler = new APPaymentGLPostingEventHandler(postingService, failures);

    private static APPaymentGLPostingEvent event() {
        return APPaymentGLPostingEvent.builder()
                .eventId(UUID.randomUUID())
                .organizationId(UUID.fromString("00000000-0000-4000-a000-000000000010"))
                .paymentId(PAYMENT_ID)
                .paymentRef("PAY-412")
                .vendorId(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f5001"))
                .grossAmount(new java.math.BigDecimal("412.00"))
                .currency("USD")
                .paymentMethod("ACH")
                .allocations(java.util.List.of())
                .build();
    }

    @Test
    @DisplayName("a posting delivers the payment by id and records nothing")
    void posts() {
        handler.onAPPaymentGLPosting(event());

        verify(postingService).postPending(PAYMENT_ID);
        verify(failures, never()).persistGLPostRefusal(any(), any());
    }

    @Test
    @DisplayName("AC6: each refusal moves the payment to GL_POST_FAILED with its code and completes the outbox row")
    void refusalsAreNotTransient() {
        Map<RuntimeException, String> refusals = Map.of(
                new GLMappingNotConfiguredException("missing"), "GL_MAPPING_NOT_CONFIGURED",
                new AccountingPeriodClosedException("2026-10", "closed"), "PERIOD_CLOSED",
                new AccountingPeriodHardLockedException(LocalDate.of(2026, 11, 1), "locked"), "PERIOD_HARD_LOCKED",
                new AccountingTimeZoneUnsetException(), "ACCOUNTING_TIME_ZONE_UNSET");
        refusals.forEach((refusal, code) -> {
            reset(postingService, failures);
            when(postingService.postPending(PAYMENT_ID)).thenThrow(refusal);

            assertThatCode(() -> handler.onAPPaymentGLPosting(event())).doesNotThrowAnyException();

            verify(failures).persistGLPostRefusal(PAYMENT_ID, code);
        });
    }

    @Test
    @DisplayName("AC7: any other exception propagates, so the outbox retries; nothing is recorded on the payment")
    void otherFailuresAreTransient() {
        CannotAcquireLockException busy = new CannotAcquireLockException("lock timeout");
        when(postingService.postPending(PAYMENT_ID)).thenThrow(busy);

        assertThatThrownBy(() -> handler.onAPPaymentGLPosting(event())).isSameAs(busy);
        verify(failures, never()).persistGLPostRefusal(any(), any());
    }
}
