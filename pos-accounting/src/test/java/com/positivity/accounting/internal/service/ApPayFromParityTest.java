package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.readmodel.BankAccountCurrencies;
import com.positivity.accounting.internal.bankrec.readmodel.BankAccountLabels;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.ApPayFromAccountListResponse;
import com.positivity.accounting.internal.dto.ExecuteAPPaymentRequest;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AP reads #2670 AC 4 and AC 5: the pay-from read and the pay command's slot 1c ask the one rule ({@link
 * ApPayFromAccounts}), so the read lists exactly the accounts the payment accepts and its default is the account an
 * omitted {@code bankAccountId} pays from. Every assertion runs through both sides, so a change to the shared rule fails
 * both, never one.
 */
@DisplayName("AP pay-from read and pay command agree on the eligible accounts (#2670 AC 4, AC 5)")
class ApPayFromParityTest {

    /** Business date D; the clock is 15:00 on D in the tenant's calendar (UTC). */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T15:00:00Z"), ZoneOffset.UTC);

    private static final LocalDate D = LocalDate.of(2026, 10, 9);

    private static final UUID A = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f1a00");
    private static final UUID B = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f1b00");
    private static final UUID C = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f1c00");
    private static final UUID E = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f1e00");
    private static final UUID F = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f1f00");
    private static final UUID G = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f1900");

    private final GLAccountRepository glAccounts = mock(GLAccountRepository.class);
    private final BankAccountCurrencies currencies = mock(BankAccountCurrencies.class);
    private final BankAccountLabels labels = mock(BankAccountLabels.class);
    private final Map<UUID, GLAccount> accounts = new HashMap<>();
    private final List<GLAccount> bankCash = new ArrayList<>();

    private ApChoicesServiceImpl read;
    private APPaymentPreGatewayChecks payment;

    @BeforeEach
    void wire() {
        LedgerCurrency usd = new LedgerCurrency("USD");
        ApPayFromAccounts rule =
                new ApPayFromAccounts(CLOCK, TestZoneResolvers.utc(CLOCK), glAccounts, currencies, usd);
        read = new ApChoicesServiceImpl(
                mock(VendorBillExpenseKeys.class), mock(GLMappingRepository.class), glAccounts, rule, labels);
        payment = new APPaymentPreGatewayChecks(
                rule,
                usd,
                mock(AccountingPeriodGate.class),
                mock(GLMappingResolver.class),
                mock(GLAccountService.class));
        lenient().when(currencies.currencyOf(any())).thenReturn(Optional.empty());
        lenient().when(currencies.currencyOf(B)).thenReturn(Optional.of("CAD"));
        lenient().when(labels.labelsOf(anyCollection())).thenReturn(Map.of());

        account(A, "1000", AccountSubtype.BANK_CASH, null, null);
        account(B, "1010", AccountSubtype.BANK_CASH, null, null);
        account(C, "1020", AccountSubtype.BANK_CASH, D.atTime(10, 0), null);
        account(E, "1030", AccountSubtype.BANK_CASH, null, D.atTime(9, 0));
        account(F, "2000", AccountSubtype.PAYABLE, null, null);
        // The query answers with its own conditions applied to the arguments it is given, over the BANK_CASH accounts.
        lenient()
                .when(glAccounts.findBySubtypeActiveAt(eq(AccountSubtype.BANK_CASH), any(), any()))
                .thenAnswer(invocation -> {
                    LocalDateTime at = invocation.getArgument(1);
                    LocalDateTime notDeactivatedBy = invocation.getArgument(2);
                    return bankCash.stream()
                            .filter(a -> a.getActivationDate() == null
                                    || !a.getActivationDate().isAfter(at))
                            .filter(a -> a.getDeactivationDate() == null
                                    || a.getDeactivationDate().isAfter(notDeactivatedBy))
                            .sorted(java.util.Comparator.comparing(GLAccount::getAccountCode))
                            .toList();
                });
    }

    private void account(
            UUID id, String code, AccountSubtype subtype, LocalDateTime activated, LocalDateTime deactivated) {
        GLAccount account = new GLAccount();
        account.setGlAccountId(id);
        account.setAccountCode(code);
        account.setAccountName("Account " + code);
        account.setAccountSubtype(subtype);
        account.setActivationDate(activated);
        account.setDeactivationDate(deactivated);
        accounts.put(id, account);
        if (subtype == AccountSubtype.BANK_CASH) {
            bankCash.add(account);
        }
        lenient().when(glAccounts.findById(id)).thenReturn(Optional.of(account));
    }

    private static ExecuteAPPaymentRequest pay(UUID bankAccountId) {
        return ExecuteAPPaymentRequest.builder()
                .paymentRef("PAY-2670")
                .vendorId(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f5001"))
                .grossAmount(new BigDecimal("100.00"))
                .currency("USD")
                .paymentMethod(PaymentMethod.ACH)
                .bankAccountId(bankAccountId)
                .build();
    }

    private UUID paymentWithout() {
        return payment.checkRequest(pay(null), Optional.of(D));
    }

    private boolean paymentAccepts(UUID bankAccountId) {
        try {
            payment.checkRequest(pay(bankAccountId), Optional.of(D));
            return true;
        } catch (VendorBillException refused) {
            return false;
        }
    }

    @Test
    @DisplayName("AC 4 (read): A, B foreign, C activated later on D, E deactivated before now, F not BANK_CASH → the"
            + " read lists A only, with default A")
    void readListsOnlyTheEligibleAccount() {
        ApPayFromAccountListResponse listed = read.payFromAccounts();

        assertThat(listed.asOf()).isEqualTo(D);
        assertThat(listed.currencyCode()).isEqualTo("USD");
        assertThat(listed.accounts())
                .extracting(ApPayFromAccountListResponse.Account::bankAccountId)
                .containsExactly(A);
        assertThat(listed.defaultBankAccountId()).isEqualTo(A);
    }

    @Test
    @DisplayName("AC 4 (payment): the same fixtures → a payment without bankAccountId pays from A, and only A is"
            + " accepted when named")
    void paymentPaysFromTheEligibleAccount() {
        assertThat(paymentWithout()).isEqualTo(A);
        assertThat(Arrays.asList(A, B, C, E, F))
                .filteredOn(this::paymentAccepts)
                .containsExactly(A);
    }

    @Test
    @DisplayName("AC 4: account by account, the read's list and the payment's own check agree")
    void readAndPaymentAgree() {
        ApPayFromAccountListResponse listed = read.payFromAccounts();
        for (UUID id : Arrays.asList(A, B, C, E, F)) {
            assertThat(paymentAccepts(id))
                    .as("account %s", accounts.get(id).getAccountCode())
                    .isEqualTo(listed.accounts().stream()
                            .anyMatch(a -> a.bankAccountId().equals(id)));
        }
        assertThat(Optional.ofNullable(listed.defaultBankAccountId())).contains(paymentWithout());
    }

    @Test
    @DisplayName("AC 4: with a second eligible account G the read lists A and G without a default, and a payment"
            + " without bankAccountId answers 400 fieldErrors[bankAccountId]")
    void twoEligibleAccounts() {
        account(G, "1005", AccountSubtype.BANK_CASH, null, null);

        ApPayFromAccountListResponse listed = read.payFromAccounts();

        assertThat(listed.accounts())
                .extracting(ApPayFromAccountListResponse.Account::bankAccountId)
                .containsExactly(A, G);
        assertThat(listed.defaultBankAccountId()).isNull();
        assertThatThrownBy(this::paymentWithout).isInstanceOfSatisfying(VendorBillException.class, refusal -> {
            assertThat(refusal.getCode().status().value()).isEqualTo(400);
            assertThat(refusal.getFieldErrors())
                    .extracting(VendorBillException.FieldError::field)
                    .containsExactly("bankAccountId");
        });
    }

    @Test
    @DisplayName("an empty list is a 200 with no default, and the payment then answers 400 fieldErrors[bankAccountId]")
    void noAccount() {
        bankCash.clear();

        ApPayFromAccountListResponse listed = read.payFromAccounts();

        assertThat(listed.accounts()).isEmpty();
        assertThat(listed.defaultBankAccountId()).isNull();
        assertThatThrownBy(this::paymentWithout).isInstanceOfSatisfying(VendorBillException.class, refusal -> {
            assertThat(refusal.getCode().status().value()).isEqualTo(400);
            assertThat(refusal.getFieldErrors())
                    .extracting(VendorBillException.FieldError::field)
                    .containsExactly("bankAccountId");
        });
    }

    @Test
    @DisplayName("AC 5: an account with a bank profile serves bankName and accountMask; one without serves both null")
    void profileLabels() {
        account(G, "1005", AccountSubtype.BANK_CASH, null, null);
        when(labels.labelsOf(anyCollection()))
                .thenReturn(Map.of(A, new BankAccountLabels.Label("First National", "4321")));

        ApPayFromAccountListResponse listed = read.payFromAccounts();

        ApPayFromAccountListResponse.Account a = listed.accounts().get(0);
        assertThat(a.bankAccountId()).isEqualTo(A);
        assertThat(a.accountNumber()).isEqualTo("1000");
        assertThat(a.accountName()).isEqualTo("Account 1000");
        assertThat(a.bankName()).isEqualTo("First National");
        assertThat(a.accountMask()).isEqualTo("4321");
        assertThat(a.toString()).as("the mask is never printed").doesNotContain("4321");
        ApPayFromAccountListResponse.Account g = listed.accounts().get(1);
        assertThat(g.bankName()).isNull();
        assertThat(g.accountMask()).isNull();
    }
}
