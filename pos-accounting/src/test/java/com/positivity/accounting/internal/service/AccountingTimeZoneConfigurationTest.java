package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.PostgresIntegrationTestBase;
import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.entity.AccountingPeriod;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.exception.AccountingTimeZoneLockedException;
import com.positivity.accounting.internal.exception.InvalidAccountingTimeZoneException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant's accounting time zone setting on real Postgres (#2558): the provisioning seed, the IANA-only validation
 * (400), the lock once a period was closed or a hard lock set (409), the audit row, and the resolver's eviction.
 */
@Transactional
@Import(TestSecurityConfig.class)
@DisplayName("Accounting time zone setting (#2558, real Postgres)")
class AccountingTimeZoneConfigurationTest extends PostgresIntegrationTestBase {

    private static final String ACTOR = "tz-admin";

    @Autowired
    private AccountingConfigurationService configurationService;

    @Autowired
    private AccountingConfigurationRepository configurationRepository;

    @Autowired
    private AccountingAuditLogRepository auditLogRepository;

    @Autowired
    private AccountingPeriodRepository periodRepository;

    @Autowired
    private AccountingCalendarZoneResolver zoneResolver;

    @BeforeEach
    void setUp() {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(ACTOR, null);
        authentication.setAuthenticated(true);
        authentication.setDetails(Map.of("username", ACTOR));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterTransaction
    void zoneRolledBackAndEvicted() {
        assertThat(storedZone())
                .as("the provisioning seed survives the rolled-back test")
                .isEqualTo("UTC");
        assertThat(zoneResolver.zone())
                .as("a rolled-back change is never read: nothing is cached")
                .isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    @DisplayName("tenant provisioning seeds UTC")
    void provisioningSeedsUtc() {
        assertThat(storedZone()).isEqualTo("UTC");
        assertThat(zoneResolver.zone()).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    @DisplayName("an IANA zone is stored, audited with old, new and actor, and read through the resolver at once")
    void setsAndAudits() {
        assertThat(zoneResolver.zone()).isEqualTo(ZoneId.of("UTC"));

        assertThat(configurationService.setAccountingTimeZone(" America/Chicago "))
                .isEqualTo("America/Chicago");

        assertThat(storedZone()).isEqualTo("America/Chicago");
        assertThat(zoneResolver.zone()).isEqualTo(ZoneId.of("America/Chicago"));
        List<AccountingAuditLog> audit = timeZoneAudit();
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().getOldValue()).isEqualTo("UTC");
        assertThat(audit.getFirst().getNewValue()).isEqualTo("America/Chicago");
        assertThat(audit.getFirst().getUserId()).isEqualTo(ACTOR);
        assertThat(audit.getFirst().getEntityType()).isEqualTo("ACCOUNTING_CONFIGURATION");
    }

    @Test
    @DisplayName("setting the current zone again writes nothing")
    void sameZoneIsANoOp() {
        assertThat(configurationService.setAccountingTimeZone("UTC")).isEqualTo("UTC");

        assertThat(timeZoneAudit()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "+05:00",
                "-06:00",
                "UTC+05:00",
                "GMT-06:00",
                "Etc/GMT+6",
                "SystemV/CST6",
                "CST",
                "Mars/Olympus",
                " "
            })
    @DisplayName("an unknown id, a fixed offset (UTC aliases included) or a SystemV id is refused with 400; nothing is"
            + " written")
    void invalidZoneIsRefused(String zone) {
        assertThatThrownBy(() -> configurationService.setAccountingTimeZone(zone))
                .isInstanceOf(InvalidAccountingTimeZoneException.class);

        assertThat(storedZone()).isEqualTo("UTC");
        assertThat(timeZoneAudit()).isEmpty();
    }

    @Test
    @DisplayName("once a period is CLOSED the zone is locked: 409 ACCOUNTING_TIME_ZONE_LOCKED")
    void lockedAfterAClose() {
        period(AccountingPeriodStatus.CLOSED);

        assertThatThrownBy(() -> configurationService.setAccountingTimeZone("America/Chicago"))
                .isInstanceOf(AccountingTimeZoneLockedException.class);
        assertThat(storedZone()).isEqualTo("UTC");
    }

    @Test
    @DisplayName("a reopened period still locks the zone: its month was cut once")
    void lockedAfterAReopen() {
        AccountingPeriod period = period(AccountingPeriodStatus.CLOSED);
        period.setStatus(AccountingPeriodStatus.OPEN);
        period.setReopenedAt(Instant.parse("2026-02-03T00:00:00Z"));
        periodRepository.saveAndFlush(period);

        assertThatThrownBy(() -> configurationService.setAccountingTimeZone("America/Chicago"))
                .isInstanceOf(AccountingTimeZoneLockedException.class);
    }

    @Test
    @DisplayName("a hard-lock date locks the zone")
    void lockedAfterAHardLock() {
        configurationService.setHardLockDate(LocalDate.of(2026, 1, 1), "FY2025 audit finalized");

        assertThatThrownBy(() -> configurationService.setAccountingTimeZone("America/Chicago"))
                .isInstanceOf(AccountingTimeZoneLockedException.class);
    }

    @Test
    @DisplayName("without an accounting time zone a hard lock fails closed (ACCOUNTING_TIME_ZONE_UNSET)")
    void hardLockWithoutAZoneFailsClosed() {
        configurationRepository
                .findByConfigKey(AccountingCalendarZoneResolver.CONFIG_KEY)
                .ifPresent(configurationRepository::delete);
        configurationRepository.flush();

        assertThatThrownBy(() -> configurationService.setHardLockDate(LocalDate.of(2026, 1, 1), "FY2025 audit"))
                .isInstanceOf(com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException.class);
        assertThat(configurationService.getHardLockDate()).isEmpty();
    }

    @Test
    @DisplayName("an open, never-closed period does not lock the zone")
    void openPeriodDoesNotLock() {
        period(AccountingPeriodStatus.OPEN);

        assertThat(configurationService.setAccountingTimeZone("America/Chicago"))
                .isEqualTo("America/Chicago");
    }

    private AccountingPeriod period(AccountingPeriodStatus status) {
        AccountingPeriod period = new AccountingPeriod();
        period.setPeriodCode("2026-01");
        period.setStartDate(LocalDate.of(2026, 1, 1));
        period.setEndDate(LocalDate.of(2026, 1, 31));
        period.setStatus(status);
        if (status == AccountingPeriodStatus.CLOSED) {
            period.setClosedAt(Instant.parse("2026-02-02T00:00:00Z"));
            period.setClosedBy(ACTOR);
        }
        return periodRepository.saveAndFlush(period);
    }

    private String storedZone() {
        return configurationRepository
                .findByConfigKey(AccountingCalendarZoneResolver.CONFIG_KEY)
                .map(AccountingConfiguration::getConfigValue)
                .orElse(null);
    }

    private List<AccountingAuditLog> timeZoneAudit() {
        return auditLogRepository.findAll().stream()
                .filter(a -> "ACCOUNTING_TIME_ZONE_SET".equals(a.getOperation()))
                .toList();
    }
}
