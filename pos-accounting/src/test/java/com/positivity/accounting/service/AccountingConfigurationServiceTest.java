package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.exception.HardLockDateRegressionException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

/**
 * H2-backed tests for the org-level hard-lock configuration (story B2,
 * issue #944): storage round trip, mandatory justification,
 * monotonic-forward-only enforcement, and HARD_LOCK_SET audit rows with the
 * acting user (ADR-0018).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@Import(TestSecurityConfig.class)
@DisplayName("AccountingConfiguration hard-lock tests (B2)")
class AccountingConfigurationServiceTest {

    private static final String ACTOR = "hard-lock-admin";

    @Autowired
    private AccountingConfigurationService configurationService;

    @Autowired
    private AccountingConfigurationRepository configurationRepository;

    @Autowired
    private AccountingAuditLogRepository auditLogRepository;

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
    void verifyRollbackCleanliness() {
        // Transactional test: configuration writes roll back with the test
        // transaction, keeping the shared H2 database pristine.
        assertThat(configurationRepository.count()).isZero();
    }

    private List<AccountingAuditLog> hardLockAuditRows() {
        return auditLogRepository.findAll().stream()
                .filter(a -> "ACCOUNTING_CONFIGURATION".equals(a.getEntityType())
                        && "HARD_LOCK_SET".equals(a.getOperation()))
                .toList();
    }

    @Test
    @DisplayName("getHardLockDate is empty when no hard lock has been set")
    void getHardLockDate_unset_isEmpty() {
        assertThat(configurationService.getHardLockDate()).isEmpty();
    }

    @Test
    @DisplayName("setHardLockDate stores the date and writes a HARD_LOCK_SET audit row with the actor")
    void setHardLockDate_storesAndAudits() {
        LocalDate hardLockDate = LocalDate.of(2018, 7, 1);

        LocalDate stored = configurationService.setHardLockDate(hardLockDate, "Fiscal H1 finalized");

        assertThat(stored).isEqualTo(hardLockDate);
        assertThat(configurationService.getHardLockDate()).contains(hardLockDate);

        List<AccountingAuditLog> auditRows = hardLockAuditRows();
        assertThat(auditRows).hasSize(1);
        AccountingAuditLog auditRow = auditRows.getFirst();
        assertThat(auditRow.getUserId()).isEqualTo(ACTOR);
        assertThat(auditRow.getJustification()).isEqualTo("Fiscal H1 finalized");
        assertThat(auditRow.getOldValue()).isNull();
        assertThat(auditRow.getNewValue()).isEqualTo("2018-07-01");
    }

    @Test
    @DisplayName("setHardLockDate moves forward and audits old -> new")
    void setHardLockDate_forward_succeeds() {
        configurationService.setHardLockDate(LocalDate.of(2018, 7, 1), "H1 close");

        configurationService.setHardLockDate(LocalDate.of(2019, 1, 1), "Fiscal 2018 close");

        assertThat(configurationService.getHardLockDate()).contains(LocalDate.of(2019, 1, 1));
        assertThat(configurationRepository.count())
                .as("single HARD_LOCK_DATE row is updated in place")
                .isEqualTo(1);

        List<AccountingAuditLog> auditRows = hardLockAuditRows();
        assertThat(auditRows).hasSize(2);
        assertThat(auditRows.getLast().getOldValue()).isEqualTo("2018-07-01");
        assertThat(auditRows.getLast().getNewValue()).isEqualTo("2019-01-01");
    }

    @Test
    @DisplayName("setHardLockDate to the same date is allowed (monotonic means >=, not >)")
    void setHardLockDate_sameDate_allowed() {
        configurationService.setHardLockDate(LocalDate.of(2018, 7, 1), "H1 close");

        LocalDate stored = configurationService.setHardLockDate(LocalDate.of(2018, 7, 1), "Re-affirm H1 close");

        assertThat(stored).isEqualTo(LocalDate.of(2018, 7, 1));
    }

    @Test
    @DisplayName("setHardLockDate backward is rejected (monotonic-forward-only) and state is unchanged")
    void setHardLockDate_backward_rejected() {
        configurationService.setHardLockDate(LocalDate.of(2019, 1, 1), "Fiscal 2018 close");

        assertThatThrownBy(() -> configurationService.setHardLockDate(LocalDate.of(2018, 6, 1), "Oops, roll it back"))
                .isInstanceOf(HardLockDateRegressionException.class)
                .hasMessageContaining("2019-01-01")
                .hasMessageContaining("2018-06-01");

        assertThat(configurationService.getHardLockDate()).contains(LocalDate.of(2019, 1, 1));
        assertThat(hardLockAuditRows()).hasSize(1);
    }

    @Test
    @DisplayName("setHardLockDate with a blank justification is rejected and nothing is stored")
    void setHardLockDate_blankJustification_rejected() {
        assertThatThrownBy(() -> configurationService.setHardLockDate(LocalDate.of(2018, 7, 1), "  "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(configurationService.getHardLockDate()).isEmpty();
        assertThat(hardLockAuditRows()).isEmpty();
    }
}
