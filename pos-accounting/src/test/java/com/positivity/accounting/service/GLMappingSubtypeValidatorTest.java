package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.service.GLMappingSubtypeValidator;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for GLMappingSubtypeValidator (Story H1, Issue #934).
 *
 * The validator is non-blocking: it only ever returns a warning message, never
 * throws. It warns when a cash-receipt/settlement external code maps to an
 * account whose subtype is present and not in the plausible set
 * (RECEIVABLE, BANK_CASH, UNDEPOSITED_FUNDS).
 */
@DisplayName("GLMappingSubtypeValidator Unit Tests")
class GLMappingSubtypeValidatorTest {

    private final GLMappingSubtypeValidator validator = new GLMappingSubtypeValidator();

    @Test
    @DisplayName("Should warn when cash-receipt code maps to an implausible subtype")
    void shouldWarnForImplausibleSubtype() {
        GLAccount account = account("4000", "Service Revenue", AccountType.REVENUE, AccountSubtype.SALES);

        Optional<String> warning = validator.checkCashReceiptSubtype("PAYMENT_APPLICATION", account);

        assertThat(warning).isPresent();
        assertThat(warning.get())
                .contains("PAYMENT_APPLICATION")
                .contains("4000")
                .contains("SALES")
                .contains("warning only");
    }

    @ParameterizedTest
    @EnumSource(
            value = AccountSubtype.class,
            names = {"RECEIVABLE", "BANK_CASH", "UNDEPOSITED_FUNDS"})
    @DisplayName("Should not warn when cash-receipt code maps to a plausible subtype")
    void shouldNotWarnForPlausibleSubtype(AccountSubtype plausible) {
        GLAccount account = account("1090", "Undeposited Funds", AccountType.ASSET, plausible);

        assertThat(validator.checkCashReceiptSubtype("SETTLEMENT_CLEARED", account))
                .isEmpty();
    }

    @Test
    @DisplayName("Should not warn when account has no subtype (backfill is optional)")
    void shouldNotWarnForNullSubtype() {
        GLAccount account = account("4000", "Service Revenue", AccountType.REVENUE, null);

        assertThat(validator.checkCashReceiptSubtype("PAYMENT_APPLICATION", account))
                .isEmpty();
    }

    @Test
    @DisplayName("Should not warn for non-cash-receipt codes regardless of subtype")
    void shouldNotWarnForUnrelatedCode() {
        GLAccount account = account("4000", "Service Revenue", AccountType.REVENUE, AccountSubtype.SALES);

        assertThat(validator.checkCashReceiptSubtype("ORDER_COMPLETED", account))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment_application", "CASH_RECEIPT_DEFAULT", "SETTLEMENT", "PAYMENT_RECEIVED_POS"})
    @DisplayName("Should recognize cash-receipt code variants case-insensitively")
    void shouldRecognizeCashReceiptVariants(String externalCode) {
        GLAccount account = account("5000", "Cost of Goods Sold", AccountType.EXPENSE, AccountSubtype.COST_OF_SALES);

        assertThat(validator.checkCashReceiptSubtype(externalCode, account)).isPresent();
    }

    private static GLAccount account(String code, String name, AccountType type, AccountSubtype subtype) {
        GLAccount account = new GLAccount();
        account.setGlAccountId(UUID.fromString("00000000-0000-4000-a000-000000000001"));
        account.setAccountCode(code);
        account.setAccountName(name);
        account.setAccountType(type);
        account.setAccountSubtype(subtype);
        return account;
    }
}
