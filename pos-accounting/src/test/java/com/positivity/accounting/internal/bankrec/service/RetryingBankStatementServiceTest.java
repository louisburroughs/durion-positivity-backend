package com.positivity.accounting.internal.bankrec.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.dto.BankStatementCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.BankStatementResponse;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.intake.ConcurrentCommitException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The manual-statement retry (#2301): a commit that lost a race on a database constraint is retried
 * once in a fresh transaction, where the request-id lookup replays the winner or answers a real
 * conflict.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RetryingBankStatementService")
class RetryingBankStatementServiceTest {

    @Mock
    private BankStatementServiceImpl delegate;

    @InjectMocks
    private RetryingBankStatementService service;

    private final BankStatementCreateRequest request = BankStatementCreateRequest.builder()
            .requestId(UUID.fromString("0190a000-0000-7000-8000-000000000002"))
            .build();

    private static ConcurrentCommitException raced() {
        return new ConcurrentCommitException(
                BankRecErrorCode.IDEMPOTENCY_CONFLICT, "The requestId was used by a concurrent request");
    }

    @Test
    void theLoserOfARaceReplaysTheWinnerOnTheRetry() {
        BankStatementResponse replay =
                BankStatementResponse.builder().replayed(true).build();
        when(delegate.createManualStatement(request)).thenThrow(raced()).thenReturn(replay);

        assertThat(service.createManualStatement(request)).isSameAs(replay);
        verify(delegate, times(2)).createManualStatement(request);
    }

    @Test
    void aSecondRaceIsAnsweredWithItsCode() {
        when(delegate.createManualStatement(request)).thenThrow(raced()).thenThrow(raced());

        assertThatThrownBy(() -> service.createManualStatement(request)).isInstanceOf(ConcurrentCommitException.class);
        verify(delegate, times(2)).createManualStatement(request);
    }

    @Test
    void anyOtherRefusalIsNotRetried() {
        when(delegate.createManualStatement(request))
                .thenThrow(new BankRecException(BankRecErrorCode.IDEMPOTENCY_CONFLICT, "different payload"));

        assertThatThrownBy(() -> service.createManualStatement(request)).hasMessage("different payload");
        verify(delegate, times(1)).createManualStatement(request);
    }
}
