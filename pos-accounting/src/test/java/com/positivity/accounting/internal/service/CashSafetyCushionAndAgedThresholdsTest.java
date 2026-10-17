package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.service.BankRecPolicy;
import com.positivity.accounting.internal.bankrec.service.FunctionalCurrency;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.AgedThresholdsRequest;
import com.positivity.accounting.internal.dto.AgedThresholdsResponse;
import com.positivity.accounting.internal.dto.CashSafetyCushionRequest;
import com.positivity.accounting.internal.dto.CashSafetyCushionResponse;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.exception.CurrencyNotSupportedException;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The cash safety cushion and aged-threshold settings (#2515): validation, storage, audit and replay. */
@DisplayName("Cash safety cushion and aged thresholds (#2515)")
class CashSafetyCushionAndAgedThresholdsTest {

    private static final String CUSHION = CashAndPayablesSettings.CASH_SAFETY_CUSHION;

    private final AccountingConfigurationRepository configuration = mock(AccountingConfigurationRepository.class);
    private final AccountingAuditLogRepository auditLogs = mock(AccountingAuditLogRepository.class);
    private final Map<String, AccountingConfiguration> rows = new LinkedHashMap<>();
    private final List<AccountingAuditLog> audits = new ArrayList<>();
    private AccountingConfigurationServiceImpl service;

    @BeforeEach
    void setUp() {
        FunctionalCurrency usd = new FunctionalCurrency(new LedgerCurrency("USD"));
        service = new AccountingConfigurationServiceImpl(
                configuration,
                auditLogs,
                mock(BankRecPolicy.class),
                usd,
                mock(AccountingPeriodRepository.class),
                new CashAndPayablesSettings(configuration));
        when(configuration.findWithLockByConfigKey(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.<String>getArgument(0))));
        when(configuration.findByConfigKeyIn(anyCollection())).thenAnswer(invocation -> {
            java.util.Collection<String> keys = invocation.getArgument(0);
            return rows.values().stream()
                    .filter(row -> keys.contains(row.getConfigKey()))
                    .toList();
        });
        when(configuration.save(any())).thenAnswer(invocation -> {
            AccountingConfiguration row = invocation.getArgument(0);
            if (row.getConfigId() == null) {
                row.setConfigId(UUID.randomUUID());
            }
            rows.put(row.getConfigKey(), row);
            return row;
        });
        doAnswer(invocation -> {
                    AccountingConfiguration row = invocation.getArgument(0);
                    rows.remove(row.getConfigKey());
                    return null;
                })
                .when(configuration)
                .delete(any());
        when(auditLogs.save(any())).thenAnswer(invocation -> {
            AccountingAuditLog row = invocation.getArgument(0);
            audits.add(row);
            return row;
        });
        when(auditLogs.findFirstByOperationAndEntityId(anyString(), any()))
                .thenAnswer(invocation -> audits.stream()
                        .filter(a -> a.getOperation().equals(invocation.getArgument(0))
                                && a.getEntityId().equals(invocation.getArgument(1)))
                        .findFirst());
    }

    private static CashSafetyCushionRequest cushion(String amount, String currency, String justification, UUID id) {
        CashSafetyCushionRequest request = new CashSafetyCushionRequest();
        request.setAmount(amount == null ? null : new BigDecimal(amount));
        request.setCurrencyCode(currency);
        request.setJustification(justification);
        request.setRequestId(id);
        return request;
    }

    private static AgedThresholdsRequest thresholds(int a, int b, int c, UUID id) {
        AgedThresholdsRequest request = new AgedThresholdsRequest();
        request.setUndepositedCashAgedDays(a);
        request.setVendorCodUnappliedAgedDays(b);
        request.setDeliveriesNotBilledDays(c);
        request.setJustification("Finance review of the aging rules");
        request.setRequestId(id);
        return request;
    }

    private List<AccountingAuditLog> audits(String operation) {
        return audits.stream().filter(a -> a.getOperation().equals(operation)).toList();
    }

    // ---- cushion ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an unset cushion reads null in the functional currency")
    void unsetCushion() {
        CashSafetyCushionResponse response = service.getCashSafetyCushion();

        assertThat(response.getAmount()).isNull();
        assertThat(response.getCurrencyCode()).isEqualTo("USD");
    }

    @Test
    @DisplayName("a cushion is stored at currency scale and audited old to new with the justification and requestId")
    void setsAndAudits() {
        UUID id = UUID.randomUUID();

        CashSafetyCushionResponse response =
                service.setCashSafetyCushion(cushion("2500", "USD", "Treasury sets one payroll", id));

        assertThat(response.getAmount()).isEqualTo(new BigDecimal("2500.00"));
        assertThat(rows.get(CUSHION).getConfigValue()).isEqualTo("2500.00");
        AccountingAuditLog set = audits("CASH_SAFETY_CUSHION_SET").getFirst();
        assertThat(set.getOldValue()).isEqualTo("CASH_SAFETY_CUSHION=");
        assertThat(set.getNewValue()).isEqualTo("CASH_SAFETY_CUSHION=2500.00;requestId=" + id);
        assertThat(set.getJustification()).isEqualTo("Treasury sets one payroll");
        assertThat(audits("CASH_SAFETY_CUSHION_REQUEST")).hasSize(1);

        UUID next = UUID.randomUUID();
        service.setCashSafetyCushion(cushion("3000.50", "USD", "Raised after the audit", next));
        AccountingAuditLog second = audits("CASH_SAFETY_CUSHION_SET").get(1);
        assertThat(second.getOldValue()).isEqualTo("CASH_SAFETY_CUSHION=2500.00");
        assertThat(second.getNewValue()).isEqualTo("CASH_SAFETY_CUSHION=3000.50;requestId=" + next);
    }

    @Test
    @DisplayName("zero is a real value; null clears the cushion and is audited")
    void zeroAndClear() {
        service.setCashSafetyCushion(cushion("0", "USD", "A zero cushion on purpose", UUID.randomUUID()));
        assertThat(rows.get(CUSHION).getConfigValue()).isEqualTo("0.00");
        assertThat(service.getCashSafetyCushion().getAmount()).isEqualByComparingTo("0");

        UUID id = UUID.randomUUID();
        CashSafetyCushionResponse cleared =
                service.setCashSafetyCushion(cushion(null, "USD", "Removing the cushion", id));

        assertThat(cleared.getAmount()).isNull();
        assertThat(rows).doesNotContainKey(CUSHION);
        assertThat(audits("CASH_SAFETY_CUSHION_SET").get(1).getNewValue())
                .isEqualTo("CASH_SAFETY_CUSHION=;requestId=" + id);
    }

    @Test
    @DisplayName("clearing an unset cushion changes and audits nothing but the request")
    void clearingUnsetIsNoChange() {
        service.setCashSafetyCushion(cushion(null, "USD", "Nothing to clear here", UUID.randomUUID()));

        assertThat(audits("CASH_SAFETY_CUSHION_SET")).isEmpty();
        assertThat(audits("CASH_SAFETY_CUSHION_REQUEST")).hasSize(1);
    }

    @Test
    @DisplayName("another currency is 422 CURRENCY_NOT_SUPPORTED and writes nothing")
    void foreignCurrencyRefused() {
        assertThatThrownBy(
                        () -> service.setCashSafetyCushion(cushion("100", "EUR", "In euros please", UUID.randomUUID())))
                .isInstanceOf(CurrencyNotSupportedException.class);

        assertThat(rows).isEmpty();
        assertThat(audits).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"100,XXQ", "100,usd", "-0.01,USD", "10.001,USD"})
    @DisplayName("an unknown code, a negative amount or too many decimals is 400 VALIDATION_ERROR")
    void invalidAmountOrCode(String amount, String currency) {
        assertThatThrownBy(() -> service.setCashSafetyCushion(
                        cushion(amount, currency, "A valid justification", UUID.randomUUID())))
                .isInstanceOf(InvalidRequestParameterException.class);

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName(
            "a missing amount key, requestId or justification is refused; 1-9 characters is JUSTIFICATION_REQUIRED")
    void missingFields() {
        CashSafetyCushionRequest noAmountKey = new CashSafetyCushionRequest();
        noAmountKey.setCurrencyCode("USD");
        noAmountKey.setJustification("A valid justification");
        noAmountKey.setRequestId(UUID.randomUUID());
        assertThatThrownBy(() -> service.setCashSafetyCushion(noAmountKey))
                .isInstanceOf(InvalidRequestParameterException.class);
        assertThatThrownBy(() -> service.setCashSafetyCushion(cushion("1", "USD", "A valid justification", null)))
                .isInstanceOf(InvalidRequestParameterException.class);
        assertThatThrownBy(() -> service.setCashSafetyCushion(cushion("1", "USD", " ", UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        BankRecException.class, e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> service.setCashSafetyCushion(cushion("1", "USD", "too short", UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        BankRecException.class,
                        e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.JUSTIFICATION_REQUIRED));
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("a replayed requestId writes nothing; the same id with another body is a 409")
    void replayAndConflict() {
        UUID id = UUID.randomUUID();
        service.setCashSafetyCushion(cushion("500", "USD", "First and only change", id));
        int auditRows = audits.size();

        CashSafetyCushionResponse replay =
                service.setCashSafetyCushion(cushion("500", "USD", "First and only change", id));

        assertThat(replay.getAmount()).isEqualByComparingTo("500");
        assertThat(audits).hasSize(auditRows);
        assertThatThrownBy(() -> service.setCashSafetyCushion(cushion("900", "USD", "First and only change", id)))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(rows.get(CUSHION).getConfigValue()).isEqualTo("500.00");
    }

    // ---- aged thresholds --------------------------------------------------------------------------------------

    @Test
    @DisplayName("a fresh tenant reads the defaults 3, 14 and 30")
    void defaults() {
        AgedThresholdsResponse response = service.getAgedThresholds();

        assertThat(response.getUndepositedCashAgedDays()).isEqualTo(3);
        assertThat(response.getVendorCodUnappliedAgedDays()).isEqualTo(14);
        assertThat(response.getDeliveriesNotBilledDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("only a threshold whose value changes is written and audited, old to new, with the requestId")
    void changedThresholdsAreAudited() {
        UUID id = UUID.randomUUID();

        AgedThresholdsResponse response = service.setAgedThresholds(thresholds(5, 14, 45, id));

        assertThat(response.getUndepositedCashAgedDays()).isEqualTo(5);
        assertThat(response.getVendorCodUnappliedAgedDays()).isEqualTo(14);
        assertThat(response.getDeliveriesNotBilledDays()).isEqualTo(45);
        assertThat(rows).containsOnlyKeys("UNDEPOSITED_CASH_AGED_DAYS", "DELIVERIES_NOT_BILLED_DAYS");
        List<AccountingAuditLog> sets = audits("AGED_THRESHOLDS_SET");
        assertThat(sets).hasSize(2);
        assertThat(sets.getFirst().getOldValue()).isEqualTo("UNDEPOSITED_CASH_AGED_DAYS=3");
        assertThat(sets.getFirst().getNewValue()).isEqualTo("UNDEPOSITED_CASH_AGED_DAYS=5;requestId=" + id);
        assertThat(sets.getFirst().getJustification()).isEqualTo("Finance review of the aging rules");
        assertThat(audits("AGED_THRESHOLDS_REQUEST")).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"0,14,30", "31,14,30", "3,0,30", "3,91,30", "3,14,6", "3,14,121"})
    @DisplayName("a value outside its range is 400 VALIDATION_ERROR and nothing is written")
    void outOfRange(int a, int b, int c) {
        assertThatThrownBy(() -> service.setAgedThresholds(thresholds(a, b, c, UUID.randomUUID())))
                .isInstanceOf(InvalidRequestParameterException.class);

        assertThat(rows).isEmpty();
        assertThat(audits).isEmpty();
    }

    @Test
    @DisplayName("the range limits themselves are accepted")
    void rangeLimitsAccepted() {
        service.setAgedThresholds(thresholds(1, 1, 7, UUID.randomUUID()));
        service.setAgedThresholds(thresholds(30, 90, 120, UUID.randomUUID()));

        AgedThresholdsResponse response = service.getAgedThresholds();
        assertThat(response.getUndepositedCashAgedDays()).isEqualTo(30);
        assertThat(response.getVendorCodUnappliedAgedDays()).isEqualTo(90);
        assertThat(response.getDeliveriesNotBilledDays()).isEqualTo(120);
    }

    @Test
    @DisplayName("a missing day count, and a short justification, are refused")
    void missingDayCount() {
        AgedThresholdsRequest missing = thresholds(3, 14, 30, UUID.randomUUID());
        missing.setDeliveriesNotBilledDays(null);
        assertThatThrownBy(() -> service.setAgedThresholds(missing))
                .isInstanceOf(InvalidRequestParameterException.class);

        AgedThresholdsRequest shortReason = thresholds(3, 14, 30, UUID.randomUUID());
        shortReason.setJustification("short");
        assertThatThrownBy(() -> service.setAgedThresholds(shortReason)).isInstanceOf(BankRecException.class);
    }

    @Test
    @DisplayName("a replayed requestId writes nothing; the same id with other values is a 409")
    void thresholdReplayAndConflict() {
        UUID id = UUID.randomUUID();
        service.setAgedThresholds(thresholds(5, 20, 40, id));
        int auditRows = audits.size();

        service.setAgedThresholds(thresholds(5, 20, 40, id));

        assertThat(audits).hasSize(auditRows);
        assertThatThrownBy(() -> service.setAgedThresholds(thresholds(6, 20, 40, id)))
                .isInstanceOf(IdempotencyConflictException.class);
    }
}
