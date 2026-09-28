package com.positivity.accounting.internal.bankrec.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.bankrec.dto.BankStatementCreateRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The manual-statement request hash tells a replay from a reuse without delimiter ambiguity (#2301). */
@DisplayName("Manual statement request hash")
class BankStatementRequestHashTest {

    private static final UUID ACCOUNT = UUID.fromString("0190a000-0000-7000-8000-000000000001");
    private static final LocalDate DAY = LocalDate.of(2026, 1, 5);

    private static BankStatementCreateRequest request(
            String description, String reference, String checkNumber, String statementRef) {
        return BankStatementCreateRequest.builder()
                .glAccountId(ACCOUNT)
                .requestId(UUID.fromString("0190a000-0000-7000-8000-000000000002"))
                .statement(BankStatementCreateRequest.Header.builder()
                        .statementRef(statementRef)
                        .startDate(DAY)
                        .endDate(DAY)
                        .openingBalance(BigDecimal.ZERO)
                        .closingBalance(new BigDecimal("10.00"))
                        .build())
                .transactions(List.of(BankStatementCreateRequest.Transaction.builder()
                        .date(DAY)
                        .signedAmount(new BigDecimal("10.00"))
                        .description(description)
                        .reference(reference)
                        .checkNumber(checkNumber)
                        .build()))
                .build();
    }

    @Test
    void theSamePayloadHashesTheSameAndAmountScaleDoesNotMatter() {
        BankStatementCreateRequest a = request("Deposit", "R1", null, "S1");
        BankStatementCreateRequest b = request("Deposit", "R1", null, "S1");
        b.getTransactions().getFirst().setSignedAmount(new BigDecimal("10.0"));
        assertThat(BankStatementServiceImpl.hash(a)).isEqualTo(BankStatementServiceImpl.hash(b));
    }

    @Test
    void aDelimiterInsideFreeTextDoesNotCollideWithAShiftedFieldBoundary() {
        BankStatementCreateRequest left = request("abcdefghij|x", "y", null, "S1");
        BankStatementCreateRequest right = request("abcdefghij", "x|y", null, "S1");
        assertThat(BankStatementServiceImpl.hash(left)).isNotEqualTo(BankStatementServiceImpl.hash(right));
    }

    @Test
    void anAbsentFieldDiffersFromTheLiteralTextNull() {
        BankStatementCreateRequest absent = request("Deposit", null, null, "S1");
        BankStatementCreateRequest literal = request("Deposit", "null", null, "S1");
        assertThat(BankStatementServiceImpl.hash(absent)).isNotEqualTo(BankStatementServiceImpl.hash(literal));
    }
}
