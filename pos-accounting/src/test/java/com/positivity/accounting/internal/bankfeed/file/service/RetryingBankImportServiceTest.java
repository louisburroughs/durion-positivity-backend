package com.positivity.accounting.internal.bankfeed.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCommitResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCreateRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportDiscardRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportResponse;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** The import service's race retry (story S3, #2302; the #2301 RetryingBankStatementService pattern). */
@ExtendWith(MockitoExtension.class)
@DisplayName("RetryingBankImportService (#2302)")
class RetryingBankImportServiceTest {

    private static final UUID IMPORT = UUID.fromString("01990000-0000-7000-8000-0000000000aa");

    @Mock
    private BankImportServiceImpl delegate;

    @InjectMocks
    private RetryingBankImportService service;

    @Test
    void anUploadThatLostTheRequestIdRaceIsRetriedOnceAndReplaysTheWinner() {
        BankImportCreateRequest request = new BankImportCreateRequest();
        BankImportResponse replay =
                BankImportResponse.builder().importId(IMPORT).replayed(true).build();
        when(delegate.create(any(), isNull(), isNull(), isNull()))
                .thenThrow(new ConcurrentCommitException(BankRecErrorCode.IDEMPOTENCY_CONFLICT, "raced"))
                .thenReturn(replay);

        assertThat(service.create(request, null, null, null)).isSameAs(replay);
        verify(delegate, times(2)).create(any(), isNull(), isNull(), isNull());
    }

    @Test
    void aCommitThatLostARaceIsRetriedOnceAndAnswersTheRetrysOutcome() {
        BankImportCommitResponse winner =
                BankImportCommitResponse.builder().importId(IMPORT).build();
        when(delegate.commit(IMPORT, null))
                .thenThrow(new ConcurrentCommitException(BankRecErrorCode.STATEMENT_ALREADY_IMPORTED, "raced"))
                .thenReturn(winner);
        assertThat(service.commit(IMPORT, null)).isSameAs(winner);

        when(delegate.commit(IMPORT, null))
                .thenThrow(new ObjectOptimisticLockingFailureException("BankImport", IMPORT))
                .thenThrow(new BankRecException(BankRecErrorCode.STATEMENT_ALREADY_IMPORTED, "the winner's window"));
        assertThatThrownBy(() -> service.commit(IMPORT, null))
                .isInstanceOfSatisfying(
                        BankRecException.class,
                        e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.STATEMENT_ALREADY_IMPORTED));
    }

    @Test
    void aSecondLossIsNotRetriedAgainAndOtherCommandsAreNeverRetried() {
        when(delegate.commit(IMPORT, null))
                .thenThrow(new ConcurrentCommitException(BankRecErrorCode.STATEMENT_PERIOD_OVERLAP, "raced"));
        assertThatThrownBy(() -> service.commit(IMPORT, null)).isInstanceOf(ConcurrentCommitException.class);
        verify(delegate, times(2)).commit(IMPORT, null);

        BankImportDiscardRequest discard = new BankImportDiscardRequest("wrong account's file", null);
        when(delegate.discard(IMPORT, discard))
                .thenThrow(new ObjectOptimisticLockingFailureException("BankImport", IMPORT));
        assertThatThrownBy(() -> service.discard(IMPORT, discard))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(delegate, times(1)).discard(IMPORT, discard);
    }
}
