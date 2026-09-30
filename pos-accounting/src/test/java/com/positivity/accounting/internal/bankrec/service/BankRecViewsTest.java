package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.ACCOUNT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.END;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.START;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.STATEMENT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.amount;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.statement;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.transaction;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.bankrec.dto.BankStatementResponse;
import com.positivity.accounting.internal.bankrec.dto.BankTransactionResponse;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.FeedChange;
import com.positivity.accounting.internal.bankrec.enums.SettlementState;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts.BankCashAccount;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link BankRecViews} (story S2, #2301): every entity field reaches the response, and the ADR-0064 display
 * values come from the bank account when one is known and stay null when it is not.
 */
@DisplayName("BankRecViews (#2301)")
class BankRecViewsTest {

    private static final BankCashAccount ACCOUNT = new BankCashAccount(ACCOUNT_ID, "1000", "Operating Cash");
    private static final Instant T0 = Instant.parse("2026-09-15T10:15:30Z");
    private static final Instant T1 = Instant.parse("2026-09-16T11:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-17T12:30:00Z");
    private static final Instant T3 = Instant.parse("2026-09-18T13:45:00Z");

    @Nested
    @DisplayName("statement")
    class Statement {

        @Test
        @DisplayName("maps every statement field and the account display values")
        void mapsEveryField() {
            BankStatement source = fullStatement();

            BankStatementResponse response =
                    BankRecViews.statement(source, ACCOUNT).build();

            assertThat(response.getStatementId()).isEqualTo(STATEMENT_ID);
            assertThat(response.getGlAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.getAccountCode()).isEqualTo("1000");
            assertThat(response.getAccountName()).isEqualTo("Operating Cash");
            assertThat(response.getSourceKind()).isEqualTo(SourceKind.FILE_IMPORT);
            assertThat(response.getSourceRef()).isEqualTo(source.getSourceRef());
            assertThat(response.getConnectorCode()).isEqualTo("OFX");
            assertThat(response.getStatementRef()).isEqualTo("STMT-2026-09");
            assertThat(response.getStartDate()).isEqualTo(START);
            assertThat(response.getEndDate()).isEqualTo(END);
            assertThat(response.getOpeningBalance()).isEqualByComparingTo("1000.0000");
            assertThat(response.getClosingBalance()).isEqualByComparingTo("1250.0000");
            assertThat(response.getActivityTotal()).isEqualByComparingTo("250.0000");
            assertThat(response.getCurrency()).isEqualTo("USD");
            assertThat(response.getGapAcknowledgement()).isEqualTo("Gap acknowledged by controller");
            assertThat(response.getGapAcknowledgedBy()).isEqualTo("controller");
            assertThat(response.getGapAcknowledgedAt()).isEqualTo(T0);
            assertThat(response.getStatus()).isEqualTo(BankStatementStatus.SUPERSEDED);
            assertThat(response.getSupersededByStatementId()).isEqualTo(source.getSupersededByStatementId());
            assertThat(response.getCreatedAt()).isEqualTo(T1);
            assertThat(response.getCreatedBy()).isEqualTo("importer");
        }

        @Test
        @DisplayName("leaves the display values null when the account is unknown")
        void unknownAccountLeavesDisplayValuesNull() {
            BankStatementResponse response =
                    BankRecViews.statement(fullStatement(), null).build();

            assertThat(response.getAccountCode()).isNull();
            assertThat(response.getAccountName()).isNull();
            assertThat(response.getGlAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.getStatementId()).isEqualTo(STATEMENT_ID);
        }

        @Test
        @DisplayName("returns an open builder so callers can add counts and links")
        void returnsOpenBuilder() {
            BankStatementResponse response = BankRecViews.statement(fullStatement(), ACCOUNT)
                    .bankTransactionCount(7L)
                    .possibleDuplicateCount(2L)
                    .replayed(true)
                    .build();

            assertThat(response.getBankTransactionCount()).isEqualTo(7L);
            assertThat(response.getPossibleDuplicateCount()).isEqualTo(2L);
            assertThat(response.isReplayed()).isTrue();
            assertThat(response.getAccountCode()).isEqualTo("1000");
        }
    }

    @Nested
    @DisplayName("transaction")
    class Transaction {

        @Test
        @DisplayName("maps every transaction field and the account display values")
        void mapsEveryField() {
            BankTransaction source = fullTransaction();

            BankTransactionResponse response = BankRecViews.transaction(source, ACCOUNT);

            assertThat(response.getBankTransactionId()).isEqualTo(source.getBankTransactionId());
            assertThat(response.getGlAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.getAccountCode()).isEqualTo("1000");
            assertThat(response.getAccountName()).isEqualTo("Operating Cash");
            assertThat(response.getStatementId()).isEqualTo(STATEMENT_ID);
            assertThat(response.getSourceKind()).isEqualTo(SourceKind.BANK_FEED);
            assertThat(response.getSourceRef()).isEqualTo(source.getSourceRef());
            assertThat(response.getConnectorCode()).isEqualTo("PLAID");
            assertThat(response.getSourceTransactionId()).isEqualTo("feed-tx-42");
            assertThat(response.getSourceRowNumber()).isEqualTo(17);
            assertThat(response.getSupersedesBankTransactionId()).isEqualTo(source.getSupersedesBankTransactionId());
            assertThat(response.getSettlementState()).isEqualTo(SettlementState.PENDING);
            assertThat(response.getTransactionDate()).isEqualTo(LocalDate.of(2026, 9, 12));
            assertThat(response.getAuthorizedDate()).isEqualTo(LocalDate.of(2026, 9, 11));
            assertThat(response.getSignedAmount()).isEqualByComparingTo("-42.5000");
            assertThat(response.getCurrency()).isEqualTo("USD");
            assertThat(response.getDescription()).isEqualTo("BANK ROW");
            assertThat(response.getOriginalDescription()).isEqualTo("Bank Row #42");
            assertThat(response.getNormalizedDescription()).isEqualTo("BANK ROW");
            assertThat(response.getReference()).isEqualTo("REF-42");
            assertThat(response.getCheckNumber()).isEqualTo("1042");
            assertThat(response.getCounterpartyName()).isEqualTo("Acme Supplies");
            assertThat(response.getCategoryHint()).isEqualTo("OFFICE");
            assertThat(response.getFingerprint()).isEqualTo("fp-abc123");
            assertThat(response.getStatus()).isEqualTo(BankTransactionStatus.EXCLUDED);
            assertThat(response.getDuplicateOfBankTransactionId()).isEqualTo(source.getDuplicateOfBankTransactionId());
            assertThat(response.isArrivedAfterApproval()).isTrue();
            assertThat(response.getExclusionReason()).isEqualTo("Duplicate of the earlier fee");
            assertThat(response.getExcludedBy()).isEqualTo("reviewer");
            assertThat(response.getExcludedAt()).isEqualTo(T0);
            assertThat(response.getFeedChange()).isEqualTo(FeedChange.MODIFIED);
            assertThat(response.getFirstObservedAt()).isEqualTo(T1);
            assertThat(response.getLastObservedAt()).isEqualTo(T2);
            assertThat(response.getRemovedAt()).isEqualTo(T3);
            assertThat(response.getVersion()).isEqualTo(5L);
            assertThat(response.getMatchId()).isNull();
            assertThat(response.getOutstandingItemId()).isNull();
        }

        @Test
        @DisplayName("leaves the display values null when the account is unknown")
        void unknownAccountLeavesDisplayValuesNull() {
            BankTransaction source = fullTransaction();

            BankTransactionResponse response = BankRecViews.transaction(source, null);

            assertThat(response.getAccountCode()).isNull();
            assertThat(response.getAccountName()).isNull();
            assertThat(response.getGlAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.getBankTransactionId()).isEqualTo(source.getBankTransactionId());
        }

        @Test
        @DisplayName("carries a row not yet excluded with its empty exclusion fields")
        void unexcludedRowKeepsNullExclusionFields() {
            BankTransaction source = transaction("100.0000", START);

            BankTransactionResponse response = BankRecViews.transaction(source, ACCOUNT);

            assertThat(response.getStatus()).isEqualTo(BankTransactionStatus.UNMATCHED);
            assertThat(response.getSignedAmount()).isEqualByComparingTo("100.0000");
            assertThat(response.getExclusionReason()).isNull();
            assertThat(response.getExcludedBy()).isNull();
            assertThat(response.getExcludedAt()).isNull();
            assertThat(response.isArrivedAfterApproval()).isFalse();
        }
    }

    private static BankStatement fullStatement() {
        BankStatement s = statement(STATEMENT_ID, START, END, "Gap acknowledged by controller");
        s.setSourceKind(SourceKind.FILE_IMPORT);
        s.setSourceRef(UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678900010"));
        s.setConnectorCode("OFX");
        s.setStatementRef("STMT-2026-09");
        s.setGapAcknowledgedBy("controller");
        s.setGapAcknowledgedAt(T0);
        s.setStatus(BankStatementStatus.SUPERSEDED);
        s.setSupersededByStatementId(UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678900011"));
        s.setCreatedAt(T1);
        s.setCreatedBy("importer");
        return s;
    }

    private static BankTransaction fullTransaction() {
        BankTransaction t = transaction("-42.5000", LocalDate.of(2026, 9, 12));
        t.setSourceKind(SourceKind.BANK_FEED);
        t.setSourceRef(UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678900020"));
        t.setConnectorCode("PLAID");
        t.setSourceTransactionId("feed-tx-42");
        t.setSourceRowNumber(17);
        t.setSupersedesBankTransactionId(UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678900021"));
        t.setSettlementState(SettlementState.PENDING);
        t.setAuthorizedDate(LocalDate.of(2026, 9, 11));
        t.setSignedAmount(amount("-42.5000"));
        t.setOriginalDescription("Bank Row #42");
        t.setReference("REF-42");
        t.setCheckNumber("1042");
        t.setCounterpartyName("Acme Supplies");
        t.setCategoryHint("OFFICE");
        t.setFingerprint("fp-abc123");
        t.setStatus(BankTransactionStatus.EXCLUDED);
        t.setDuplicateOfBankTransactionId(UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678900022"));
        t.setArrivedAfterApproval(true);
        t.setExclusionReason("Duplicate of the earlier fee");
        t.setExcludedBy("reviewer");
        t.setExcludedAt(T0);
        t.setFeedChange(FeedChange.MODIFIED);
        t.setFirstObservedAt(T1);
        t.setLastObservedAt(T2);
        t.setRemovedAt(T3);
        t.setVersion(5L);
        return t;
    }
}
