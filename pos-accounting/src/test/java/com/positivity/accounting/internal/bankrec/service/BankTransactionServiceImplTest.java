package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.ACCOUNT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.START;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.STATEMENT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.statement;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.transaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.dto.BankTransactionBatchResponse;
import com.positivity.accounting.internal.bankrec.dto.BankTransactionJustificationRequest;
import com.positivity.accounting.internal.bankrec.dto.BankTransactionListResponse;
import com.positivity.accounting.internal.bankrec.dto.BankTransactionResponse;
import com.positivity.accounting.internal.bankrec.dto.DuplicateReviewDecision;
import com.positivity.accounting.internal.bankrec.dto.DuplicateReviewRequest;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts.BankCashAccount;
import com.positivity.security.common.GatewaySecurityConstants;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.Invocation;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link BankTransactionServiceImpl} (SPEC §3.8, §4.5, §6.1; story S2, #2301): the list filters and paging,
 * the justified duplicate review, exclude and restore transitions, their guards (version, state, superseded
 * statement, finalized period) and the audit trail each transition leaves.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BankTransactionServiceImpl (#2301)")
class BankTransactionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private static final BankCashAccount ACCOUNT = new BankCashAccount(ACCOUNT_ID, "1000", "Operating Cash");
    private static final String WHY = "Two separate monthly fees on the same day";
    private static final Sort SORT = Sort.by("transactionDate", "bankTransactionId");

    @Mock
    private BankTransactionRepository transactions;

    @Mock
    private BankReconciliationRepository reconciliations;

    @Mock
    private BankCashAccounts bankCashAccounts;

    @Mock
    private BankRecAuditRecorder audit;

    @Mock
    private BankStatementRepository statements;

    private BankTransactionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BankTransactionServiceImpl(
                transactions, reconciliations, bankCashAccounts, audit, statements, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(bankCashAccounts.displayValues(any())).thenReturn(Map.of(ACCOUNT_ID, ACCOUNT));
        lenient().when(transactions.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    // ---- listTransactions ---------------------------------------------------------------------

    @Nested
    @DisplayName("listTransactions")
    class ListTransactions {

        @Test
        @DisplayName("requires the GL account id")
        void requiresGlAccountId() {
            assertThatThrownBy(() -> service.listTransactions(null, null, null, null, null, false, 0, 20))
                    .isInstanceOfSatisfying(BankRecException.class, e -> {
                        assertThat(e.code()).isEqualTo(BankRecErrorCode.VALIDATION_ERROR);
                        assertThat(e.fieldErrors()).containsEntry("glAccountId", "is required");
                    });
            verifyNoInteractions(transactions);
        }

        @Test
        @DisplayName("refuses a from date after the to date")
        void refusesInvertedRange() {
            LocalDate from = LocalDate.of(2026, 9, 20);
            LocalDate to = LocalDate.of(2026, 9, 10);

            assertThatThrownBy(() -> service.listTransactions(ACCOUNT_ID, null, from, to, null, false, 0, 20))
                    .isInstanceOfSatisfying(BankRecException.class, e -> {
                        assertThat(e.code()).isEqualTo(BankRecErrorCode.VALIDATION_ERROR);
                        assertThat(e.fieldErrors()).containsEntry("from", "must not be after to");
                    });
            verifyNoInteractions(transactions);
        }

        @Test
        @DisplayName("accepts a single-day range and applies every filter")
        void appliesEveryFilter() {
            LocalDate day = LocalDate.of(2026, 9, 15);
            BankTransaction row = transaction("-10.0000", day);
            stubPage(List.of(row), PageRequest.of(0, 20, SORT), 1);

            BankTransactionListResponse response = service.listTransactions(
                    ACCOUNT_ID,
                    BankTransactionStatus.POSSIBLE_DUPLICATE,
                    day,
                    day,
                    SourceKind.FILE_IMPORT,
                    true,
                    0,
                    20);

            assertThat(response.getTransactions()).singleElement().satisfies(r -> {
                assertThat(r.getBankTransactionId()).isEqualTo(row.getBankTransactionId());
                assertThat(r.getAccountCode()).isEqualTo("1000");
                assertThat(r.getAccountName()).isEqualTo("Operating Cash");
            });

            SpecProbe probe = SpecProbe.run(capturedSpec());
            assertThat(probe.predicateCount()).isEqualTo(6);
            verify(probe.cb).equal(probe.path("glAccountId"), ACCOUNT_ID);
            verify(probe.cb).equal(probe.path("status"), BankTransactionStatus.POSSIBLE_DUPLICATE);
            verify(probe.path("status")).in(BankTransactionServiceImpl.UNEXPLAINED);
            verify(probe.cb).greaterThanOrEqualTo(probe.<LocalDate>path("transactionDate"), day);
            verify(probe.cb).lessThanOrEqualTo(probe.<LocalDate>path("transactionDate"), day);
            verify(probe.cb).equal(probe.path("sourceKind"), SourceKind.FILE_IMPORT);
        }

        @Test
        @DisplayName("filters on the account alone when no optional filter is given")
        void accountOnly() {
            stubPage(List.of(), PageRequest.of(0, 20, SORT), 0);

            BankTransactionListResponse response =
                    service.listTransactions(ACCOUNT_ID, null, null, null, null, false, 0, 20);

            assertThat(response.getTransactions()).isEmpty();
            assertThat(response.getTotalElements()).isZero();

            SpecProbe probe = SpecProbe.run(capturedSpec());
            assertThat(probe.predicateCount()).isEqualTo(1);
            verify(probe.cb).equal(probe.path("glAccountId"), ACCOUNT_ID);
            assertThat(probe.touched()).containsExactly("glAccountId");
        }

        @Test
        @DisplayName("an open-ended from bound adds only the lower date predicate")
        void fromOnly() {
            LocalDate from = LocalDate.of(2026, 9, 5);
            stubPage(List.of(), PageRequest.of(0, 20, SORT), 0);

            service.listTransactions(ACCOUNT_ID, null, from, null, null, false, 0, 20);

            SpecProbe probe = SpecProbe.run(capturedSpec());
            assertThat(probe.predicateCount()).isEqualTo(2);
            verify(probe.cb).greaterThanOrEqualTo(probe.<LocalDate>path("transactionDate"), from);
            verify(probe.cb, never()).lessThanOrEqualTo(probe.<LocalDate>path("transactionDate"), from);
        }

        @Test
        @DisplayName("an open-ended to bound adds only the upper date predicate")
        void toOnly() {
            LocalDate to = LocalDate.of(2026, 9, 25);
            stubPage(List.of(), PageRequest.of(0, 20, SORT), 0);

            service.listTransactions(ACCOUNT_ID, null, null, to, null, false, 0, 20);

            SpecProbe probe = SpecProbe.run(capturedSpec());
            assertThat(probe.predicateCount()).isEqualTo(2);
            verify(probe.cb).lessThanOrEqualTo(probe.<LocalDate>path("transactionDate"), to);
            verify(probe.cb, never()).greaterThanOrEqualTo(probe.<LocalDate>path("transactionDate"), to);
        }

        @Test
        @DisplayName("pages by transaction date then id and reports the page metadata")
        void pagesWithStableSort() {
            BankTransaction first = transaction("5.0000", START);
            BankTransaction second = transaction("6.0000", START.plusDays(1));
            stubPage(List.of(first, second), PageRequest.of(1, 2, SORT), 5);

            BankTransactionListResponse response =
                    service.listTransactions(ACCOUNT_ID, null, START, null, null, false, 1, 2);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(transactions).findAll(any(Specification.class), pageable.capture());
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(2);
            assertThat(pageable.getValue().getSort()).isEqualTo(SORT);

            assertThat(response.getTransactions())
                    .extracting(BankTransactionResponse::getBankTransactionId)
                    .containsExactly(first.getBankTransactionId(), second.getBankTransactionId());
            assertThat(response.getTotalElements()).isEqualTo(5);
            assertThat(response.getPageNumber()).isEqualTo(1);
            assertThat(response.getPageSize()).isEqualTo(2);
            assertThat(response.getTotalPages()).isEqualTo(3);
        }

        @Test
        @DisplayName("leaves display values empty when the account is not a known bank account")
        void unknownAccountDisplayValues() {
            UUID other = UUID.fromString("5eed0acc-0000-4000-8000-000000009999");
            BankTransaction row = transaction("1.0000", START);
            row.setGlAccountId(other);
            stubPage(List.of(row), PageRequest.of(0, 20, SORT), 1);
            when(bankCashAccounts.displayValues(List.of(other))).thenReturn(Map.of());

            BankTransactionListResponse response =
                    service.listTransactions(other, null, null, null, null, false, 0, 20);

            assertThat(response.getTransactions()).singleElement().satisfies(r -> {
                assertThat(r.getGlAccountId()).isEqualTo(other);
                assertThat(r.getAccountCode()).isNull();
                assertThat(r.getAccountName()).isNull();
            });
        }

        @Test
        @DisplayName("refuses a page size beyond the maximum before querying")
        void refusesOversizedPage() {
            assertThatThrownBy(() -> service.listTransactions(ACCOUNT_ID, null, null, null, null, false, 0, 500))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.fieldErrors()).containsKey("size"));
            verify(transactions, never()).findAll(any(Specification.class), any(Pageable.class));
        }

        private void stubPage(List<BankTransaction> rows, PageRequest request, long total) {
            when(transactions.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(rows, request, total));
        }

        @SuppressWarnings("unchecked")
        private Specification<BankTransaction> capturedSpec() {
            ArgumentCaptor<Specification<BankTransaction>> spec = ArgumentCaptor.forClass(Specification.class);
            verify(transactions).findAll(spec.capture(), any(Pageable.class));
            return spec.getValue();
        }
    }

    // ---- getTransaction -----------------------------------------------------------------------

    @Nested
    @DisplayName("getTransaction")
    class GetTransaction {

        @Test
        @DisplayName("returns the row with its account display values")
        void returnsRow() {
            BankTransaction row = transaction("12.3400", START);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));

            BankTransactionResponse response = service.getTransaction(row.getBankTransactionId());

            assertThat(response.getBankTransactionId()).isEqualTo(row.getBankTransactionId());
            assertThat(response.getSignedAmount()).isEqualByComparingTo("12.3400");
            assertThat(response.getAccountCode()).isEqualTo("1000");
            verify(bankCashAccounts).displayValues(List.of(ACCOUNT_ID));
        }

        @Test
        @DisplayName("answers BANK_TRANSACTION_NOT_FOUND for an unknown id")
        void notFound() {
            UUID id = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678909999");
            when(transactions.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getTransaction(id)).isInstanceOfSatisfying(BankRecException.class, e -> {
                assertThat(e.code()).isEqualTo(BankRecErrorCode.BANK_TRANSACTION_NOT_FOUND);
                assertThat(e.getMessage()).contains(id.toString());
            });
        }
    }

    // ---- reviewDuplicate ----------------------------------------------------------------------

    @Nested
    @DisplayName("reviewDuplicate")
    class ReviewDuplicate {

        @Test
        @DisplayName("DISTINCT returns the row to UNMATCHED, clears the original and audits the transition")
        void distinctReturnsToUnmatched() {
            authenticate("reviewer");
            BankTransaction original = transaction("-10.0000", START);
            BankTransaction row = possibleDuplicate(original);
            row.setVersion(3L);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));

            BankTransactionResponse response = service.reviewDuplicate(
                    row.getBankTransactionId(), request(DuplicateReviewDecision.DISTINCT, null, "  " + WHY + "  ", 3L));

            assertThat(response.getStatus()).isEqualTo(BankTransactionStatus.UNMATCHED);
            assertThat(response.getDuplicateOfBankTransactionId()).isNull();
            assertThat(response.getExclusionReason()).isNull();
            assertThat(response.getExcludedBy()).isNull();
            verify(transactions).saveAndFlush(row);
            verify(audit)
                    .record(
                            BankRecAuditRecorder.BANK_TRANSACTION,
                            row.getBankTransactionId(),
                            BankRecAuditRecorder.BANK_TRANSACTION_DUPLICATE_REVIEW,
                            "reviewer",
                            WHY,
                            "POSSIBLE_DUPLICATE",
                            "UNMATCHED");
        }

        @Test
        @DisplayName("DUPLICATE without a named original excludes against the row the intake flagged")
        void duplicateDefaultsToFlaggedOriginal() {
            BankTransaction original = transaction("-10.0000", START);
            BankTransaction row = possibleDuplicate(original);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));
            when(transactions.findById(original.getBankTransactionId())).thenReturn(Optional.of(original));

            BankTransactionResponse response = service.reviewDuplicate(
                    row.getBankTransactionId(), request(DuplicateReviewDecision.DUPLICATE, null, WHY, null));

            assertThat(response.getStatus()).isEqualTo(BankTransactionStatus.EXCLUDED);
            assertThat(response.getDuplicateOfBankTransactionId()).isEqualTo(original.getBankTransactionId());
            assertThat(response.getExclusionReason()).isEqualTo(WHY);
            assertThat(response.getExcludedBy()).isEqualTo("SYSTEM");
            assertThat(response.getExcludedAt()).isEqualTo(NOW);
            verify(audit)
                    .record(
                            BankRecAuditRecorder.BANK_TRANSACTION,
                            row.getBankTransactionId(),
                            BankRecAuditRecorder.BANK_TRANSACTION_DUPLICATE_REVIEW,
                            "SYSTEM",
                            WHY,
                            "POSSIBLE_DUPLICATE",
                            "EXCLUDED duplicateOf=" + original.getBankTransactionId());
        }

        @Test
        @DisplayName("DUPLICATE with a named original replaces the flagged one")
        void duplicateUsesRequestedOriginal() {
            authenticate("reviewer");
            BankTransaction flagged = transaction("-10.0000", START);
            BankTransaction chosen = transaction("-10.0000", START.plusDays(1));
            BankTransaction row = possibleDuplicate(flagged);
            row.setVersion(1L);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));
            when(transactions.findById(chosen.getBankTransactionId())).thenReturn(Optional.of(chosen));

            BankTransactionResponse response = service.reviewDuplicate(
                    row.getBankTransactionId(),
                    request(DuplicateReviewDecision.DUPLICATE, chosen.getBankTransactionId(), WHY, 1L));

            assertThat(response.getStatus()).isEqualTo(BankTransactionStatus.EXCLUDED);
            assertThat(response.getDuplicateOfBankTransactionId()).isEqualTo(chosen.getBankTransactionId());
            assertThat(response.getExcludedBy()).isEqualTo("reviewer");
            verify(transactions, never()).findById(flagged.getBankTransactionId());
        }

        @Test
        @DisplayName("DUPLICATE refuses a row that names no original")
        void duplicateWithoutAnyOriginal() {
            BankTransaction row = possibleDuplicate(null);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));

            assertThatThrownBy(() -> service.reviewDuplicate(
                            row.getBankTransactionId(), request(DuplicateReviewDecision.DUPLICATE, null, WHY, null)))
                    .isInstanceOfSatisfying(BankRecException.class, e -> {
                        assertThat(e.code()).isEqualTo(BankRecErrorCode.VALIDATION_ERROR);
                        assertThat(e.fieldErrors()).containsEntry("duplicateOfBankTransactionId", "is required");
                    });
            assertUnchanged(row);
        }

        @Test
        @DisplayName("DUPLICATE refuses naming the row itself as its original")
        void duplicateOfItself() {
            BankTransaction row = possibleDuplicate(null);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));

            assertThatThrownBy(() -> service.reviewDuplicate(
                            row.getBankTransactionId(),
                            request(DuplicateReviewDecision.DUPLICATE, row.getBankTransactionId(), WHY, null)))
                    .isInstanceOfSatisfying(BankRecException.class, e -> assertInvalidOriginal(e));
            verify(transactions, times(1)).findById(row.getBankTransactionId());
            assertUnchanged(row);
        }

        @Test
        @DisplayName("DUPLICATE refuses an original that does not exist")
        void duplicateOfUnknownOriginal() {
            UUID missing = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678908888");
            BankTransaction row = possibleDuplicate(null);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));
            when(transactions.findById(missing)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reviewDuplicate(
                            row.getBankTransactionId(), request(DuplicateReviewDecision.DUPLICATE, missing, WHY, null)))
                    .isInstanceOfSatisfying(BankRecException.class, e -> assertInvalidOriginal(e));
            assertUnchanged(row);
        }

        @Test
        @DisplayName("DUPLICATE refuses an original on another account")
        void duplicateOfOtherAccountRow() {
            BankTransaction foreign = transaction("-10.0000", START);
            foreign.setGlAccountId(UUID.fromString("5eed0acc-0000-4000-8000-000000002000"));
            BankTransaction row = possibleDuplicate(foreign);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));
            when(transactions.findById(foreign.getBankTransactionId())).thenReturn(Optional.of(foreign));

            assertThatThrownBy(() -> service.reviewDuplicate(
                            row.getBankTransactionId(), request(DuplicateReviewDecision.DUPLICATE, null, WHY, null)))
                    .isInstanceOfSatisfying(BankRecException.class, e -> assertInvalidOriginal(e));
            assertUnchanged(row);
        }

        @Test
        @DisplayName("refuses a row that is not a POSSIBLE_DUPLICATE")
        void refusesNonDuplicate() {
            BankTransaction row = transaction("-10.0000", START);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));

            assertThatThrownBy(() -> service.reviewDuplicate(
                            row.getBankTransactionId(), request(DuplicateReviewDecision.DISTINCT, null, WHY, null)))
                    .isInstanceOfSatisfying(BankRecException.class, e -> {
                        assertThat(e.code()).isEqualTo(BankRecErrorCode.RECONCILIATION_LINE_INELIGIBLE);
                        assertThat(e.getMessage())
                                .contains(row.getBankTransactionId().toString())
                                .contains("is UNMATCHED")
                                .contains("only a POSSIBLE_DUPLICATE row can be reviewed");
                    });
            assertThat(row.getStatus()).isEqualTo(BankTransactionStatus.UNMATCHED);
            verify(transactions, never()).saveAndFlush(any());
            verifyNoInteractions(audit);
        }

        @Test
        @DisplayName("refuses a stale version with OPTIMISTIC_LOCK")
        void refusesStaleVersion() {
            BankTransaction row = possibleDuplicate(null);
            row.setVersion(4L);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));

            assertThatThrownBy(() -> service.reviewDuplicate(
                            row.getBankTransactionId(), request(DuplicateReviewDecision.DISTINCT, null, WHY, 3L)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.OPTIMISTIC_LOCK));
            assertUnchanged(row);
        }

        @Test
        @DisplayName("requires a decision before reading the row")
        void requiresDecision() {
            UUID id = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678907777");

            assertThatThrownBy(() -> service.reviewDuplicate(id, request(null, null, WHY, null)))
                    .isInstanceOfSatisfying(BankRecException.class, e -> {
                        assertThat(e.code()).isEqualTo(BankRecErrorCode.VALIDATION_ERROR);
                        assertThat(e.fieldErrors()).containsEntry("decision", "DISTINCT or DUPLICATE");
                    });
            verifyNoInteractions(transactions, audit);
        }

        @Test
        @DisplayName("requires a justification before reading the row")
        void requiresJustification() {
            UUID id = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678907777");

            assertThatThrownBy(() ->
                            service.reviewDuplicate(id, request(DuplicateReviewDecision.DISTINCT, null, null, null)))
                    .isInstanceOfSatisfying(BankRecException.class, e -> {
                        assertThat(e.code()).isEqualTo(BankRecErrorCode.VALIDATION_ERROR);
                        assertThat(e.fieldErrors()).containsKey("justification");
                    });
            verifyNoInteractions(transactions, audit);
        }

        @Test
        @DisplayName("refuses a justification shorter than ten characters")
        void refusesShortJustification() {
            UUID id = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678907777");

            assertThatThrownBy(() -> service.reviewDuplicate(
                            id, request(DuplicateReviewDecision.DISTINCT, null, "too short", null)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.JUSTIFICATION_REQUIRED));
            verifyNoInteractions(transactions, audit);
        }

        @Test
        @DisplayName("answers BANK_TRANSACTION_NOT_FOUND for an unknown row")
        void notFound() {
            UUID id = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678907777");
            when(transactions.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                            service.reviewDuplicate(id, request(DuplicateReviewDecision.DISTINCT, null, WHY, null)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.BANK_TRANSACTION_NOT_FOUND));
            verifyNoInteractions(audit);
        }
    }

    // ---- reviewDuplicates ---------------------------------------------------------------------

    @Nested
    @DisplayName("reviewDuplicates")
    class ReviewDuplicates {

        @Test
        @DisplayName("reviews each distinct id once, in request order")
        void reviewsEachDistinctIdOnce() {
            BankTransaction a = possibleDuplicate(null);
            BankTransaction b = possibleDuplicate(null);
            when(transactions.findById(a.getBankTransactionId())).thenReturn(Optional.of(a));
            when(transactions.findById(b.getBankTransactionId())).thenReturn(Optional.of(b));
            DuplicateReviewRequest request = request(DuplicateReviewDecision.DISTINCT, null, WHY, 99L);
            request.setIds(List.of(b.getBankTransactionId(), a.getBankTransactionId(), b.getBankTransactionId()));

            BankTransactionBatchResponse response = service.reviewDuplicates(request);

            assertThat(response.getTransactions())
                    .extracting(BankTransactionResponse::getBankTransactionId)
                    .containsExactly(b.getBankTransactionId(), a.getBankTransactionId());
            assertThat(response.getTransactions())
                    .extracting(BankTransactionResponse::getStatus)
                    .containsOnly(BankTransactionStatus.UNMATCHED);
            verify(transactions, times(1)).findById(b.getBankTransactionId());
            verify(transactions, times(2)).saveAndFlush(any());
            verify(audit, times(2))
                    .record(
                            eq(BankRecAuditRecorder.BANK_TRANSACTION),
                            any(),
                            eq(BankRecAuditRecorder.BANK_TRANSACTION_DUPLICATE_REVIEW),
                            eq("SYSTEM"),
                            eq(WHY),
                            eq("POSSIBLE_DUPLICATE"),
                            eq("UNMATCHED"));
        }

        @Test
        @DisplayName("DUPLICATE marks every row excluded against the shared original")
        void duplicateAgainstSharedOriginal() {
            BankTransaction original = transaction("-10.0000", START);
            BankTransaction a = possibleDuplicate(null);
            when(transactions.findById(a.getBankTransactionId())).thenReturn(Optional.of(a));
            when(transactions.findById(original.getBankTransactionId())).thenReturn(Optional.of(original));
            DuplicateReviewRequest request =
                    request(DuplicateReviewDecision.DUPLICATE, original.getBankTransactionId(), WHY, null);
            request.setIds(List.of(a.getBankTransactionId()));

            BankTransactionBatchResponse response = service.reviewDuplicates(request);

            assertThat(response.getTransactions()).singleElement().satisfies(r -> {
                assertThat(r.getStatus()).isEqualTo(BankTransactionStatus.EXCLUDED);
                assertThat(r.getDuplicateOfBankTransactionId()).isEqualTo(original.getBankTransactionId());
                assertThat(r.getExclusionReason()).isEqualTo(WHY);
            });
        }

        @Test
        @DisplayName("requires ids")
        void requiresIds() {
            DuplicateReviewRequest request = request(DuplicateReviewDecision.DISTINCT, null, WHY, null);

            assertThatThrownBy(() -> service.reviewDuplicates(request))
                    .isInstanceOfSatisfying(BankRecException.class, e -> {
                        assertThat(e.code()).isEqualTo(BankRecErrorCode.VALIDATION_ERROR);
                        assertThat(e.fieldErrors()).containsEntry("ids", "at least one id is required");
                    });
            verifyNoInteractions(transactions);
        }

        @Test
        @DisplayName("refuses an empty id list")
        void refusesEmptyIds() {
            DuplicateReviewRequest request = request(DuplicateReviewDecision.DISTINCT, null, WHY, null);
            request.setIds(List.of());

            assertThatThrownBy(() -> service.reviewDuplicates(request))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.fieldErrors()).containsKey("ids"));
            verifyNoInteractions(transactions);
        }

        @Test
        @DisplayName("requires a decision")
        void requiresDecision() {
            DuplicateReviewRequest request = request(null, null, WHY, null);
            request.setIds(List.of(UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678907777")));

            assertThatThrownBy(() -> service.reviewDuplicates(request))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.fieldErrors()).containsKey("decision"));
            verifyNoInteractions(transactions);
        }

        @Test
        @DisplayName("stops at the first ineligible row")
        void stopsAtIneligibleRow() {
            BankTransaction eligible = possibleDuplicate(null);
            BankTransaction ineligible = transaction("-10.0000", START);
            when(transactions.findById(ineligible.getBankTransactionId())).thenReturn(Optional.of(ineligible));
            DuplicateReviewRequest request = request(DuplicateReviewDecision.DISTINCT, null, WHY, null);
            request.setIds(List.of(ineligible.getBankTransactionId(), eligible.getBankTransactionId()));

            assertThatThrownBy(() -> service.reviewDuplicates(request))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.RECONCILIATION_LINE_INELIGIBLE));
            verify(transactions, never()).findById(eligible.getBankTransactionId());
            verify(transactions, never()).saveAndFlush(any());
        }
    }

    // ---- exclude ------------------------------------------------------------------------------

    @Nested
    @DisplayName("exclude")
    class Exclude {

        @Test
        @DisplayName("excludes an UNMATCHED row with the actor, time and reason, and audits it")
        void excludesUnmatchedRow() {
            authenticate("preparer");
            BankTransaction row = transaction("-25.0000", START);
            row.setVersion(2L);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));

            BankTransactionResponse response = service.exclude(row.getBankTransactionId(), justification(WHY, 2L));

            assertThat(response.getStatus()).isEqualTo(BankTransactionStatus.EXCLUDED);
            assertThat(response.getExclusionReason()).isEqualTo(WHY);
            assertThat(response.getExcludedBy()).isEqualTo("preparer");
            assertThat(response.getExcludedAt()).isEqualTo(NOW);
            verify(transactions).saveAndFlush(row);
            verify(audit)
                    .record(
                            BankRecAuditRecorder.BANK_TRANSACTION,
                            row.getBankTransactionId(),
                            BankRecAuditRecorder.BANK_TRANSACTION_EXCLUDE,
                            "preparer",
                            WHY,
                            "UNMATCHED",
                            "EXCLUDED");
        }

        @Test
        @DisplayName("records SYSTEM as the actor for an anonymous caller")
        void anonymousActorIsSystem() {
            SecurityContextHolder.getContext()
                    .setAuthentication(new AnonymousAuthenticationToken(
                            "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
            BankTransaction row = transaction("-25.0000", START);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));

            BankTransactionResponse response = service.exclude(row.getBankTransactionId(), justification(WHY, null));

            assertThat(response.getExcludedBy()).isEqualTo("SYSTEM");
        }

        @Test
        @DisplayName("refuses a row that is not UNMATCHED")
        void refusesNonUnmatchedRow() {
            BankTransaction row = transaction("-25.0000", START);
            row.setStatus(BankTransactionStatus.MATCHED);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));

            assertThatThrownBy(() -> service.exclude(row.getBankTransactionId(), justification(WHY, null)))
                    .isInstanceOfSatisfying(BankRecException.class, e -> {
                        assertThat(e.code()).isEqualTo(BankRecErrorCode.RECONCILIATION_LINE_INELIGIBLE);
                        assertThat(e.getMessage()).contains("is MATCHED").contains("only an UNMATCHED row");
                    });
            assertThat(row.getStatus()).isEqualTo(BankTransactionStatus.MATCHED);
            verify(transactions, never()).saveAndFlush(any());
            verifyNoInteractions(audit);
        }

        @Test
        @DisplayName("refuses a stale version with OPTIMISTIC_LOCK")
        void refusesStaleVersion() {
            BankTransaction row = transaction("-25.0000", START);
            row.setVersion(7L);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));

            assertThatThrownBy(() -> service.exclude(row.getBankTransactionId(), justification(WHY, 6L)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.OPTIMISTIC_LOCK));
            assertThat(row.getStatus()).isEqualTo(BankTransactionStatus.UNMATCHED);
            verifyNoInteractions(audit);
        }

        @Test
        @DisplayName("requires a justification before reading the row")
        void requiresJustification() {
            UUID id = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678907777");

            assertThatThrownBy(() -> service.exclude(id, justification("   ", null)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.VALIDATION_ERROR));
            verifyNoInteractions(transactions, audit);
        }
    }

    // ---- restore ------------------------------------------------------------------------------

    @Nested
    @DisplayName("restore")
    class Restore {

        @Test
        @DisplayName("restores an EXCLUDED row to UNMATCHED, clears the exclusion and audits it")
        void restoresExcludedRow() {
            authenticate("controller");
            BankTransaction row = excluded("Excluded by mistake earlier");
            row.setDuplicateOfBankTransactionId(UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678906666"));
            row.setVersion(8L);
            BankStatement committed = statement(STATEMENT_ID, START, START.plusDays(29), null);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));
            when(statements.findById(STATEMENT_ID)).thenReturn(Optional.of(committed));
            when(reconciliations
                            .existsByGlAccount_GlAccountIdAndStatusAndStatementStartDateLessThanEqualAndStatementEndDateGreaterThanEqual(
                                    ACCOUNT_ID, ReconciliationStatus.FINALIZED, START, START))
                    .thenReturn(false);

            BankTransactionResponse response = service.restore(row.getBankTransactionId(), justification(WHY, 8L));

            assertThat(response.getStatus()).isEqualTo(BankTransactionStatus.UNMATCHED);
            assertThat(response.getExclusionReason()).isNull();
            assertThat(response.getExcludedBy()).isNull();
            assertThat(response.getExcludedAt()).isNull();
            assertThat(response.getDuplicateOfBankTransactionId()).isNull();
            verify(transactions).saveAndFlush(row);
            verify(audit)
                    .record(
                            BankRecAuditRecorder.BANK_TRANSACTION,
                            row.getBankTransactionId(),
                            BankRecAuditRecorder.BANK_TRANSACTION_RESTORE,
                            "controller",
                            WHY,
                            "EXCLUDED",
                            "UNMATCHED");
        }

        @Test
        @DisplayName("restores a feed row that has no statement without looking one up")
        void restoresStatementlessRow() {
            BankTransaction row = excluded("Excluded by mistake earlier");
            row.setStatementId(null);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));

            BankTransactionResponse response = service.restore(row.getBankTransactionId(), justification(WHY, null));

            assertThat(response.getStatus()).isEqualTo(BankTransactionStatus.UNMATCHED);
            verifyNoInteractions(statements);
            verify(audit)
                    .record(
                            BankRecAuditRecorder.BANK_TRANSACTION,
                            row.getBankTransactionId(),
                            BankRecAuditRecorder.BANK_TRANSACTION_RESTORE,
                            "SYSTEM",
                            WHY,
                            "EXCLUDED",
                            "UNMATCHED");
        }

        @Test
        @DisplayName("restores a row whose statement can no longer be found")
        void restoresWhenStatementMissing() {
            BankTransaction row = excluded("Excluded by mistake earlier");
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));
            when(statements.findById(STATEMENT_ID)).thenReturn(Optional.empty());

            BankTransactionResponse response = service.restore(row.getBankTransactionId(), justification(WHY, null));

            assertThat(response.getStatus()).isEqualTo(BankTransactionStatus.UNMATCHED);
        }

        @Test
        @DisplayName("refuses a row excluded by statement supersession without reading the statement")
        void refusesSupersededReason() {
            BankTransaction row = excluded(StatementSupersession.STATEMENT_SUPERSEDED);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));

            assertThatThrownBy(() -> service.restore(row.getBankTransactionId(), justification(WHY, null)))
                    .isInstanceOfSatisfying(BankRecException.class, e -> {
                        assertThat(e.code()).isEqualTo(BankRecErrorCode.RECONCILIATION_LINE_INELIGIBLE);
                        assertThat(e.getMessage()).contains("superseded statement");
                    });
            verifyNoInteractions(statements, reconciliations, audit);
            assertThat(row.getStatus()).isEqualTo(BankTransactionStatus.EXCLUDED);
        }

        @Test
        @DisplayName("refuses a row whose statement was superseded")
        void refusesRowOfSupersededStatement() {
            BankTransaction row = excluded("Excluded by mistake earlier");
            BankStatement superseded = statement(STATEMENT_ID, START, START.plusDays(29), null);
            superseded.setStatus(BankStatementStatus.SUPERSEDED);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));
            when(statements.findById(STATEMENT_ID)).thenReturn(Optional.of(superseded));

            assertThatThrownBy(() -> service.restore(row.getBankTransactionId(), justification(WHY, null)))
                    .isInstanceOfSatisfying(BankRecException.class, e -> {
                        assertThat(e.code()).isEqualTo(BankRecErrorCode.RECONCILIATION_LINE_INELIGIBLE);
                        assertThat(e.getMessage()).contains("corrected statement replaced it");
                    });
            verifyNoInteractions(reconciliations, audit);
            verify(transactions, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("refuses a row dated inside a FINALIZED reconciliation")
        void refusesFinalizedPeriod() {
            BankTransaction row = excluded("Excluded by mistake earlier");
            row.setStatementId(null);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));
            when(reconciliations
                            .existsByGlAccount_GlAccountIdAndStatusAndStatementStartDateLessThanEqualAndStatementEndDateGreaterThanEqual(
                                    ACCOUNT_ID, ReconciliationStatus.FINALIZED, START, START))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.restore(row.getBankTransactionId(), justification(WHY, null)))
                    .isInstanceOfSatisfying(BankRecException.class, e -> {
                        assertThat(e.code()).isEqualTo(BankRecErrorCode.RECONCILIATION_LINE_INELIGIBLE);
                        assertThat(e.getMessage()).contains("a FINALIZED reconciliation covers " + START);
                    });
            assertThat(row.getStatus()).isEqualTo(BankTransactionStatus.EXCLUDED);
            assertThat(row.getExclusionReason()).isEqualTo("Excluded by mistake earlier");
            verifyNoInteractions(audit);
        }

        @Test
        @DisplayName("refuses a row that is not EXCLUDED")
        void refusesNonExcludedRow() {
            BankTransaction row = transaction("-25.0000", START);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));

            assertThatThrownBy(() -> service.restore(row.getBankTransactionId(), justification(WHY, null)))
                    .isInstanceOfSatisfying(BankRecException.class, e -> {
                        assertThat(e.code()).isEqualTo(BankRecErrorCode.RECONCILIATION_LINE_INELIGIBLE);
                        assertThat(e.getMessage()).contains("only an EXCLUDED row can be restored");
                    });
            verifyNoInteractions(statements, reconciliations, audit);
        }

        @Test
        @DisplayName("refuses a stale version with OPTIMISTIC_LOCK")
        void refusesStaleVersion() {
            BankTransaction row = excluded("Excluded by mistake earlier");
            row.setVersion(2L);
            when(transactions.findById(row.getBankTransactionId())).thenReturn(Optional.of(row));

            assertThatThrownBy(() -> service.restore(row.getBankTransactionId(), justification(WHY, 1L)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.OPTIMISTIC_LOCK));
            verifyNoInteractions(statements, reconciliations, audit);
        }

        @Test
        @DisplayName("refuses a short justification before reading the row")
        void refusesShortJustification() {
            UUID id = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678907777");

            assertThatThrownBy(() -> service.restore(id, justification("oops", null)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.JUSTIFICATION_REQUIRED));
            verifyNoInteractions(transactions, audit);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Authenticates {@code username} the way the gateway filter does: the username in the details map. */
    private static void authenticate(String username) {
        UsernamePasswordAuthenticationToken token =
                UsernamePasswordAuthenticationToken.authenticated(username, "n/a", List.of());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static BankTransaction possibleDuplicate(BankTransaction original) {
        BankTransaction row = transaction("-10.0000", START);
        row.setStatus(BankTransactionStatus.POSSIBLE_DUPLICATE);
        row.setDuplicateOfBankTransactionId(original == null ? null : original.getBankTransactionId());
        return row;
    }

    private static BankTransaction excluded(String reason) {
        BankTransaction row = transaction("-25.0000", START);
        row.setStatus(BankTransactionStatus.EXCLUDED);
        row.setExclusionReason(reason);
        row.setExcludedBy("preparer");
        row.setExcludedAt(NOW.minusSeconds(3600));
        return row;
    }

    private static DuplicateReviewRequest request(
            DuplicateReviewDecision decision, UUID original, String justification, Long version) {
        return DuplicateReviewRequest.builder()
                .decision(decision)
                .duplicateOfBankTransactionId(original)
                .justification(justification)
                .version(version)
                .build();
    }

    private static BankTransactionJustificationRequest justification(String text, Long version) {
        return BankTransactionJustificationRequest.builder()
                .justification(text)
                .version(version)
                .build();
    }

    private static void assertInvalidOriginal(BankRecException e) {
        assertThat(e.code()).isEqualTo(BankRecErrorCode.VALIDATION_ERROR);
        assertThat(e.fieldErrors())
                .containsEntry("duplicateOfBankTransactionId", "another bank transaction on the same account");
    }

    private void assertUnchanged(BankTransaction row) {
        assertThat(row.getStatus()).isEqualTo(BankTransactionStatus.POSSIBLE_DUPLICATE);
        assertThat(row.getExclusionReason()).isNull();
        verify(transactions, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    /**
     * Runs a captured {@link Specification} against mocked criteria objects and records which attribute paths it
     * touched and how many predicates it combined.
     */
    private static final class SpecProbe {

        final CriteriaBuilder cb;
        private final Map<String, Path<?>> paths = new HashMap<>();
        private final List<String> touched = new ArrayList<>();

        private SpecProbe() {
            this.cb = mock(
                    CriteriaBuilder.class,
                    inv -> Predicate.class.equals(inv.getMethod().getReturnType()) ? mock(Predicate.class) : null);
        }

        @SuppressWarnings("unchecked")
        static SpecProbe run(Specification<BankTransaction> spec) {
            SpecProbe probe = new SpecProbe();
            Root<BankTransaction> root = mock(Root.class);
            CriteriaQuery<?> query = mock(CriteriaQuery.class);
            when(root.get(anyString())).thenAnswer(inv -> {
                String name = inv.getArgument(0);
                probe.touched.add(name);
                return probe.paths.computeIfAbsent(name, k -> mock(Path.class));
            });
            spec.toPredicate(root, query, probe.cb);
            return probe;
        }

        @SuppressWarnings("unchecked")
        <T> Path<T> path(String name) {
            return (Path<T>) paths.get(name);
        }

        List<String> touched() {
            return touched;
        }

        int predicateCount() {
            Invocation and = mockingDetails(cb).getInvocations().stream()
                    .filter(inv -> inv.getMethod().getName().equals("and"))
                    .reduce((first, second) -> second)
                    .orElseThrow();
            return ((Predicate[]) and.getRawArguments()[0]).length;
        }
    }
}
