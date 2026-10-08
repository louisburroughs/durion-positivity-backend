package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.readmodel.BankAccountCurrencies;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.ExecuteAPPaymentRequest;
import com.positivity.accounting.internal.entity.AccountingPeriod;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException;
import com.positivity.accounting.internal.exception.CurrencyNotSupportedException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.security.AccountingPermissions;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The AP pay command's own refusals (CAP:550 S42, #2603): slot 1 (method, currency, bank account) and slot 5 (time
 * zone, hard lock, closed period, mapping), each in its ruled order, against the production period gate.
 */
@DisplayName("AP pay command: slots 1 and 5 of the pre-gateway block (S42, #2603)")
class APPaymentPreGatewayChecksTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T15:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);
    private static final UUID VENDOR = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f5001");
    private static final UUID USD_BANK = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f1000");
    private static final UUID USD_BANK_2 = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f1010");
    private static final UUID CAD_BANK = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f1020");
    private static final UUID INACTIVE_BANK = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f1030");
    private static final UUID PAYABLES = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f2000");
    private static final UUID FEES = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f6030");

    private final GLAccountRepository glAccounts = mock(GLAccountRepository.class);
    private final BankAccountCurrencies bankAccountCurrencies = mock(BankAccountCurrencies.class);
    private final AccountingPeriodRepository periods = mock(AccountingPeriodRepository.class);
    private final AccountingConfigurationService configuration = mock(AccountingConfigurationService.class);
    private final AccountingAuditLogRepository auditLogs = mock(AccountingAuditLogRepository.class);
    private final GLMappingResolver mappings = mock(GLMappingResolver.class);
    private final AccountingPeriodService periodService = mock(AccountingPeriodService.class);

    private final java.util.Map<UUID, GLAccount> accounts = new java.util.HashMap<>();

    private APPaymentPreGatewayChecks checks;

    @BeforeEach
    void wire() {
        checks = checks(TestZoneResolvers.utc(CLOCK));
        lenient().when(configuration.getHardLockDate()).thenReturn(Optional.empty());
        lenient().when(periods.findWithLockByPeriodCode(anyString())).thenReturn(Optional.empty());
        lenient().when(periods.findWithShareLockByPeriodCode(anyString())).thenReturn(Optional.empty());
        lenient().when(bankAccountCurrencies.currencyOf(any())).thenReturn(Optional.empty());
        lenient().when(bankAccountCurrencies.currencyOf(CAD_BANK)).thenReturn(Optional.of("CAD"));
        lenient()
                .when(mappings.resolveGLAccount(eq("AP_PAYMENT"), eq("ACCOUNTS_PAYABLE"), any()))
                .thenReturn(PAYABLES);
        lenient()
                .when(mappings.resolveGLAccount(eq("AP_PAYMENT"), eq("PAYMENT_FEES"), any()))
                .thenReturn(FEES);
        account(USD_BANK, AccountSubtype.BANK_CASH, null);
        account(USD_BANK_2, AccountSubtype.BANK_CASH, null);
        account(CAD_BANK, AccountSubtype.BANK_CASH, null);
        account(INACTIVE_BANK, AccountSubtype.BANK_CASH, LocalDateTime.of(2026, 1, 1, 0, 0));
        account(PAYABLES, AccountSubtype.PAYABLE, null);
        eligibleOnToday(USD_BANK, CAD_BANK);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private APPaymentPreGatewayChecks checks(AccountingCalendarZoneResolver zoneResolver) {
        AccountingPeriodGate gate =
                new AccountingPeriodGate(periodService, periods, configuration, auditLogs, zoneResolver);
        return new APPaymentPreGatewayChecks(
                CLOCK, zoneResolver, glAccounts, bankAccountCurrencies, new LedgerCurrency("USD"), gate, mappings);
    }

    private void account(UUID id, AccountSubtype subtype, LocalDateTime deactivated) {
        GLAccount account = new GLAccount();
        account.setGlAccountId(id);
        account.setAccountCode(id.toString().substring(32));
        account.setAccountSubtype(subtype);
        account.setDeactivationDate(deactivated);
        accounts.put(id, account);
        lenient().when(glAccounts.findById(id)).thenReturn(Optional.of(account));
    }

    /** What the active-on-date query returns: the inactive account is the query's to drop. */
    private void eligibleOnToday(UUID... ids) {
        List<GLAccount> active = java.util.Arrays.stream(ids).map(accounts::get).toList();
        lenient()
                .when(glAccounts.findBySubtypeActiveOnDay(
                        AccountSubtype.BANK_CASH,
                        TODAY.atStartOfDay(),
                        TODAY.plusDays(1).atStartOfDay()))
                .thenReturn(active);
    }

    private static ExecuteAPPaymentRequest request(PaymentMethod method, String currency, UUID bankAccountId) {
        return ExecuteAPPaymentRequest.builder()
                .paymentRef("PAY-S42")
                .vendorId(VENDOR)
                .grossAmount(new BigDecimal("412.00"))
                .feeAmount(new BigDecimal("1.50"))
                .currency(currency)
                .paymentMethod(method)
                .bankAccountId(bankAccountId)
                .build();
    }

    private static void signIn(String... authorities) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        "payer.pat",
                        null,
                        java.util.Arrays.stream(authorities)
                                .map(SimpleGrantedAuthority::new)
                                .toList()));
    }

    private void closed(String periodCode) {
        AccountingPeriod period = new AccountingPeriod();
        period.setStatus(AccountingPeriodStatus.CLOSED);
        // Slot 5 reads the period under a share lock: payments of one month never wait for each other.
        when(periods.findWithShareLockByPeriodCode(periodCode)).thenReturn(Optional.of(period));
    }

    private static VendorBillException.Code code(Throwable refusal) {
        return ((VendorBillException) refusal).getCode();
    }

    @Nested
    @DisplayName("Slot 1: method, then currency, then bank account")
    class SlotOne {

        @Test
        @DisplayName("1a: CREDIT_CARD and OTHER → 422 AP_PAYMENT_METHOD_NOT_SUPPORTED (OI-17)")
        void cardAndOtherAreRefused() {
            for (PaymentMethod method : List.of(PaymentMethod.CREDIT_CARD, PaymentMethod.OTHER)) {
                assertThatThrownBy(() -> checks.checkRequest(request(method, "USD", USD_BANK), Optional.of(TODAY)))
                        .isInstanceOf(VendorBillException.class)
                        .extracting(APPaymentPreGatewayChecksTest::code)
                        .isEqualTo(VendorBillException.Code.AP_PAYMENT_METHOD_NOT_SUPPORTED);
            }
        }

        @Test
        @DisplayName("AC4: CREDIT_CARD with an ineligible bankAccountId answers the method, and no account is read")
        void methodComesBeforeTheBankAccount() {
            assertThatThrownBy(() -> checks.checkRequest(
                            request(PaymentMethod.CREDIT_CARD, "EUR", INACTIVE_BANK), Optional.of(TODAY)))
                    .isInstanceOf(VendorBillException.class)
                    .extracting(APPaymentPreGatewayChecksTest::code)
                    .isEqualTo(VendorBillException.Code.AP_PAYMENT_METHOD_NOT_SUPPORTED);
            verify(glAccounts, never()).findById(any());
            verify(glAccounts, never()).findBySubtypeActiveOnDay(any(), any(), any());
        }

        @Test
        @DisplayName("1b: EUR for a USD tenant → 422 CURRENCY_NOT_SUPPORTED, before the bank account (ruling 2)")
        void foreignCurrencyComesBeforeTheBankAccount() {
            assertThatThrownBy(() ->
                            checks.checkRequest(request(PaymentMethod.ACH, "EUR", INACTIVE_BANK), Optional.of(TODAY)))
                    .isInstanceOf(CurrencyNotSupportedException.class)
                    .hasMessageContaining("EUR");
            verify(glAccounts, never()).findById(any());
        }

        @Test
        @DisplayName("1b: a code that is not ISO 4217 → 400 VALIDATION_ERROR fieldErrors[currency] (ADR-0067 R-3)")
        void unknownCurrencyCodeIsMalformed() {
            assertThatThrownBy(() -> checks.checkRequest(request(PaymentMethod.ACH, "ZZZ", null), Optional.of(TODAY)))
                    .isInstanceOfSatisfying(VendorBillException.class, refusal -> {
                        assertThat(refusal.getCode()).isEqualTo(VendorBillException.Code.VALIDATION_ERROR);
                        assertThat(refusal.getFieldErrors())
                                .extracting(VendorBillException.FieldError::field)
                                .containsExactly("currency");
                    });
        }

        @Test
        @DisplayName("AC2a: no bankAccountId, one active USD account beside an inactive USD and an active CAD one →"
                + " the active USD account")
        void defaultsToTheOneEligibleAccount() {
            assertThat(checks.checkRequest(request(PaymentMethod.ACH, "USD", null), Optional.of(TODAY)))
                    .isEqualTo(USD_BANK);
            assertThat(checks.checkRequest(request(PaymentMethod.CHECK, "usd", null), Optional.of(TODAY)))
                    .isEqualTo(USD_BANK);
        }

        @Test
        @DisplayName("AC2b: two active USD accounts and none sent → 400 VALIDATION_ERROR fieldErrors[bankAccountId]")
        void severalEligibleAccountsNeedAChoice() {
            eligibleOnToday(USD_BANK, USD_BANK_2, CAD_BANK);

            assertThatThrownBy(() -> checks.checkRequest(request(PaymentMethod.WIRE, "USD", null), Optional.of(TODAY)))
                    .isInstanceOfSatisfying(VendorBillException.class, refusal -> {
                        assertThat(refusal.getCode()).isEqualTo(VendorBillException.Code.VALIDATION_ERROR);
                        assertThat(refusal.getCode().status().value()).isEqualTo(400);
                        assertThat(refusal.getFieldErrors())
                                .extracting(VendorBillException.FieldError::field)
                                .containsExactly("bankAccountId");
                        assertThat(refusal.getMessage()).contains("2 active USD bank accounts");
                    });
        }

        @Test
        @DisplayName("1c: no eligible account and none sent → 400 fieldErrors[bankAccountId]")
        void noEligibleAccount() {
            eligibleOnToday(CAD_BANK);

            assertThatThrownBy(() -> checks.checkRequest(request(PaymentMethod.ACH, "USD", null), Optional.of(TODAY)))
                    .isInstanceOfSatisfying(
                            VendorBillException.class,
                            refusal -> assertThat(refusal.getFieldErrors())
                                    .extracting(VendorBillException.FieldError::field)
                                    .containsExactly("bankAccountId"));
        }

        @Test
        @DisplayName("1c: a supplied account is used when eligible, refused when inactive, foreign, not BANK_CASH or"
                + " unknown")
        void suppliedAccountMustBeEligible() {
            assertThat(checks.checkRequest(request(PaymentMethod.ACH, "USD", USD_BANK_2), Optional.of(TODAY)))
                    .isEqualTo(USD_BANK_2);
            for (UUID refused : List.of(INACTIVE_BANK, CAD_BANK, PAYABLES, UUID.randomUUID())) {
                assertThatThrownBy(() ->
                                checks.checkRequest(request(PaymentMethod.ACH, "USD", refused), Optional.of(TODAY)))
                        .as("account %s", refused)
                        .isInstanceOfSatisfying(VendorBillException.class, refusal -> {
                            assertThat(refusal.getCode()).isEqualTo(VendorBillException.Code.VALIDATION_ERROR);
                            assertThat(refusal.getFieldErrors())
                                    .extracting(VendorBillException.FieldError::field)
                                    .containsExactly("bankAccountId");
                        });
            }
        }

        @Test
        @DisplayName("defaultBankAccount: the one eligible account, or empty with none or several")
        void defaultBankAccount() {
            assertThat(checks.defaultBankAccount(TODAY)).contains(USD_BANK);
            eligibleOnToday(USD_BANK, USD_BANK_2);
            assertThat(checks.defaultBankAccount(TODAY)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Slot 5: time zone, then hard lock, then closed period, then mapping")
    class SlotFive {

        @Test
        @DisplayName("5a: no accounting time zone → 422 ACCOUNTING_TIME_ZONE_UNSET, before anything else is read")
        void unsetZoneFailsClosed() {
            APPaymentPreGatewayChecks unset = checks(TestZoneResolvers.unset(CLOCK));
            assertThat(unset.businessDate()).isEmpty();

            assertThatThrownBy(() -> unset.checkPeriodAndMapping(Optional.empty(), BigDecimal.ONE, null))
                    .isInstanceOf(AccountingTimeZoneUnsetException.class);
            verifyNoInteractions(periods, mappings);
        }

        @Test
        @DisplayName("the business date is today in the tenant's accounting calendar")
        void businessDateIsTheTenantsToday() {
            assertThat(checks.businessDate()).contains(TODAY);
            assertThat(checks(TestZoneResolvers.fixed(java.time.ZoneId.of("Pacific/Auckland"), CLOCK))
                            .businessDate())
                    .contains(TODAY.plusDays(1));
        }

        @Test
        @DisplayName("5b: a hard-locked date → 422 PERIOD_HARD_LOCKED, even when its period is closed and the mapping"
                + " missing")
        void hardLockComesBeforeTheClosedPeriodAndTheMapping() {
            when(configuration.getHardLockDate()).thenReturn(Optional.of(TODAY.plusDays(1)));
            closed("2026-10");
            when(mappings.resolveGLAccount(eq("AP_PAYMENT"), eq("ACCOUNTS_PAYABLE"), any()))
                    .thenThrow(new GLMappingNotConfiguredException("missing"));

            assertThatThrownBy(() -> checks.checkPeriodAndMapping(Optional.of(TODAY), BigDecimal.ZERO, null))
                    .isInstanceOf(AccountingPeriodHardLockedException.class);
            verifyNoInteractions(mappings);
        }

        @Test
        @DisplayName("5c: a closed period without an override → 422 PERIOD_CLOSED, before the mapping")
        void closedPeriodComesBeforeTheMapping() {
            closed("2026-10");
            when(mappings.resolveGLAccount(eq("AP_PAYMENT"), eq("ACCOUNTS_PAYABLE"), any()))
                    .thenThrow(new GLMappingNotConfiguredException("missing"));

            assertThatThrownBy(() -> checks.checkPeriodAndMapping(Optional.of(TODAY), BigDecimal.ZERO, null))
                    .isInstanceOf(AccountingPeriodClosedException.class);
            verifyNoInteractions(mappings);
        }

        @Test
        @DisplayName("5c: an override without accounting:period:override is refused PERIOD_CLOSED")
        void overrideNeedsTheAuthority() {
            closed("2026-10");
            signIn(AccountingPermissions.AP_PAY);

            assertThatThrownBy(() -> checks.checkPeriodAndMapping(
                            Optional.of(TODAY), BigDecimal.ZERO, "Supplier paid on the agreed date"))
                    .isInstanceOf(AccountingPeriodClosedException.class)
                    .hasMessageContaining(AccountingPermissions.PERIOD_OVERRIDE);
        }

        @Test
        @DisplayName("AC5: a closed period with an override and accounting:period:override is accepted; nothing is"
                + " audited yet (the posting audits it)")
        void overrideIsAccepted() {
            closed("2026-10");
            signIn(AccountingPermissions.AP_PAY, AccountingPermissions.PERIOD_OVERRIDE);

            APPaymentPreGatewayChecks.Execution execution = checks.checkPeriodAndMapping(
                    Optional.of(TODAY), BigDecimal.ZERO, "Supplier paid on the agreed date");

            assertThat(execution).isEqualTo(new APPaymentPreGatewayChecks.Execution(TODAY, true));
            verifyNoInteractions(auditLogs);
            // The month is provisioned before it is share-locked, so a missing row cannot be closed mid-gateway.
            org.mockito.InOrder order = org.mockito.Mockito.inOrder(periodService, periods);
            order.verify(periodService).ensurePeriodExists(TODAY);
            order.verify(periods).findWithShareLockByPeriodCode("2026-10");
        }

        @Test
        @DisplayName("an open period accepts no override: none is stored")
        void openPeriodNeedsNoOverride() {
            signIn(AccountingPermissions.AP_PAY, AccountingPermissions.PERIOD_OVERRIDE);

            assertThat(checks.checkPeriodAndMapping(
                            Optional.of(TODAY), BigDecimal.ZERO, "Supplier paid on the agreed date"))
                    .isEqualTo(new APPaymentPreGatewayChecks.Execution(TODAY, false));
        }

        @Test
        @DisplayName("5d: a missing AP_PAYMENT/ACCOUNTS_PAYABLE mapping → 422 GL_MAPPING_NOT_CONFIGURED naming it")
        void missingPayablesMapping() {
            when(mappings.resolveGLAccount(eq("AP_PAYMENT"), eq("ACCOUNTS_PAYABLE"), any()))
                    .thenThrow(new GLMappingNotConfiguredException("No effective mapping"));

            assertThatThrownBy(() -> checks.checkPeriodAndMapping(Optional.of(TODAY), BigDecimal.ZERO, null))
                    .isInstanceOfSatisfying(GLMappingNotConfiguredException.class, refusal -> {
                        assertThat(refusal.getReferenceId()).isEqualTo("AP_PAYMENT/ACCOUNTS_PAYABLE");
                        assertThat(refusal.getNextAction()).contains("AP_PAYMENT/ACCOUNTS_PAYABLE");
                    });
            verify(mappings).resolveGLAccount("AP_PAYMENT", "ACCOUNTS_PAYABLE", TODAY.atStartOfDay());
        }

        @Test
        @DisplayName("5d: a missing PAYMENT_FEES mapping refuses a fee above zero, never a fee of 0.00")
        void feesMappingOnlyForAFee() {
            when(mappings.resolveGLAccount(eq("AP_PAYMENT"), eq("PAYMENT_FEES"), any()))
                    .thenThrow(new GLMappingNotConfiguredException("No effective mapping"));

            assertThatThrownBy(() -> checks.checkPeriodAndMapping(Optional.of(TODAY), new BigDecimal("1.50"), null))
                    .isInstanceOfSatisfying(
                            GLMappingNotConfiguredException.class,
                            refusal -> assertThat(refusal.getReferenceId()).isEqualTo("AP_PAYMENT/PAYMENT_FEES"));
            assertThat(checks.checkPeriodAndMapping(Optional.of(TODAY), new BigDecimal("0.00"), null))
                    .isEqualTo(new APPaymentPreGatewayChecks.Execution(TODAY, false));
            assertThat(checks.checkPeriodAndMapping(Optional.of(TODAY), null, null))
                    .isEqualTo(new APPaymentPreGatewayChecks.Execution(TODAY, false));
        }
    }
}
