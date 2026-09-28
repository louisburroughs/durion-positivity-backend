package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.BankStatementResponse;
import com.positivity.accounting.internal.bankrec.dto.BankTransactionResponse;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts.BankCashAccount;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Entity → response mapping of the bank statement and transaction reads (story S2, #2301). */
final class BankRecViews {

    private BankRecViews() {}

    static BankStatementResponse.BankStatementResponseBuilder statement(
            @NonNull BankStatement statement, @Nullable BankCashAccount account) {
        return BankStatementResponse.builder()
                .statementId(statement.getStatementId())
                .glAccountId(statement.getGlAccountId())
                .accountCode(account == null ? null : account.accountCode())
                .accountName(account == null ? null : account.accountName())
                .sourceKind(statement.getSourceKind())
                .sourceRef(statement.getSourceRef())
                .connectorCode(statement.getConnectorCode())
                .statementRef(statement.getStatementRef())
                .startDate(statement.getStartDate())
                .endDate(statement.getEndDate())
                .openingBalance(statement.getOpeningBalance())
                .closingBalance(statement.getClosingBalance())
                .activityTotal(statement.getActivityTotal())
                .currency(statement.getCurrency())
                .gapAcknowledgement(statement.getGapAcknowledgement())
                .gapAcknowledgedBy(statement.getGapAcknowledgedBy())
                .gapAcknowledgedAt(statement.getGapAcknowledgedAt())
                .status(statement.getStatus())
                .supersededByStatementId(statement.getSupersededByStatementId())
                .createdAt(statement.getCreatedAt())
                .createdBy(statement.getCreatedBy());
    }

    static BankTransactionResponse transaction(@NonNull BankTransaction row, @Nullable BankCashAccount account) {
        return BankTransactionResponse.builder()
                .bankTransactionId(row.getBankTransactionId())
                .glAccountId(row.getGlAccountId())
                .accountCode(account == null ? null : account.accountCode())
                .accountName(account == null ? null : account.accountName())
                .statementId(row.getStatementId())
                .sourceKind(row.getSourceKind())
                .sourceRef(row.getSourceRef())
                .connectorCode(row.getConnectorCode())
                .sourceTransactionId(row.getSourceTransactionId())
                .sourceRowNumber(row.getSourceRowNumber())
                .supersedesBankTransactionId(row.getSupersedesBankTransactionId())
                .settlementState(row.getSettlementState())
                .transactionDate(row.getTransactionDate())
                .authorizedDate(row.getAuthorizedDate())
                .signedAmount(row.getSignedAmount())
                .currency(row.getCurrency())
                .description(row.getDescription())
                .originalDescription(row.getOriginalDescription())
                .normalizedDescription(row.getNormalizedDescription())
                .reference(row.getReference())
                .checkNumber(row.getCheckNumber())
                .counterpartyName(row.getCounterpartyName())
                .categoryHint(row.getCategoryHint())
                .fingerprint(row.getFingerprint())
                .status(row.getStatus())
                .duplicateOfBankTransactionId(row.getDuplicateOfBankTransactionId())
                .arrivedAfterApproval(row.isArrivedAfterApproval())
                .exclusionReason(row.getExclusionReason())
                .excludedBy(row.getExcludedBy())
                .excludedAt(row.getExcludedAt())
                .feedChange(row.getFeedChange())
                .firstObservedAt(row.getFirstObservedAt())
                .lastObservedAt(row.getLastObservedAt())
                .removedAt(row.getRemovedAt())
                .version(row.getVersion())
                .build();
    }
}
