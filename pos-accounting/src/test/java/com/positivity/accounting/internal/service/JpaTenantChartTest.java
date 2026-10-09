package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.StatementLineMapping;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.OperationType;
import com.positivity.accounting.internal.enums.StatementType;
import com.positivity.accounting.internal.repository.DefaultGLMappingRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.repository.MappingKeyRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;
import com.positivity.accounting.internal.repository.StatementLineMappingRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The chart adapter's statement-line rules (#2526, review round 1): the template deals in global
 * lines only ({@code location_id} null, #731), and a line it creates names the account properly.
 */
@DisplayName("JpaTenantChart statement lines")
class JpaTenantChartTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("01990000-0000-7000-8000-00000000a000");
    private static final AccountingTemplate.StatementLine TEMPLATE_LINE = new AccountingTemplate.StatementLine(
            StatementType.INCOME_STATEMENT, "4000", "REVENUE", null, "REVENUE", 1, OperationType.SUM);

    private final GLAccountRepository accounts = mock(GLAccountRepository.class);
    private final StatementLineMappingRepository statementLines = mock(StatementLineMappingRepository.class);
    private final JpaTenantChart chart = new JpaTenantChart(
            accounts,
            mock(PostingCategoryRepository.class),
            mock(MappingKeyRepository.class),
            mock(GLMappingRepository.class),
            mock(DefaultGLMappingRepository.class),
            statementLines,
            mock(com.positivity.accounting.internal.repository.PettyExpenseCategoryRepository.class),
            mock(com.positivity.accounting.internal.repository.PettyExpenseCategoryChangeRepository.class),
            null,
            mock(com.positivity.accounting.internal.repository.PettyExpenseCategoryTaxSettingRepository.class),
            mock(com.positivity.accounting.internal.repository.PettyExpenseCategoryTaxSettingChangeRepository.class),
            mock(jakarta.persistence.EntityManager.class),
            java.time.Clock.systemUTC());
    private final GLAccount revenue = account();

    @Test
    @DisplayName("a tenant that holds only a location override has no line to adopt: a global line is to be created")
    void overrideOnlyIsNotAdopted() {
        when(statementLines.findByGlAccount_GlAccountId(ACCOUNT_ID)).thenReturn(List.of(line("TULSA")));

        assertThat(chart.findStatementLine(StatementType.INCOME_STATEMENT, ACCOUNT_ID))
                .isEmpty();
    }

    @Test
    @DisplayName("with a global line and an override, the global line is the one adopted, whatever the ids say")
    void globalLineIsAdoptedBesideAnOverride() {
        StatementLineMapping override = line("TULSA");
        override.setMappingId(UUID.fromString("00000000-0000-7000-8000-000000000001"));
        StatementLineMapping global = line(null);
        global.setMappingId(UUID.fromString("ffffffff-ffff-7fff-8fff-ffffffffffff"));
        when(statementLines.findByGlAccount_GlAccountId(ACCOUNT_ID)).thenReturn(List.of(override, global));

        assertThat(chart.findStatementLine(StatementType.INCOME_STATEMENT, ACCOUNT_ID))
                .map(TenantChart.LineRow::id)
                .contains(global.getMappingId());
    }

    @Test
    @DisplayName("a refresh never reaches a location override, even by id")
    void overrideIsNeverRefreshed() {
        StatementLineMapping override = line("TULSA");
        override.setMappingId(UUID.randomUUID());
        when(statementLines.findById(override.getMappingId())).thenReturn(Optional.of(override));

        assertThat(chart.findStatementLine(override.getMappingId())).isEmpty();
    }

    @Test
    @DisplayName("a created line is global and names the account by its name, not its code")
    void createdLineIsGlobalAndNamesTheAccount() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(revenue));
        when(accounts.getReferenceById(ACCOUNT_ID)).thenReturn(revenue);
        when(statementLines.save(any())).thenAnswer(invocation -> {
            StatementLineMapping saved = invocation.getArgument(0);
            saved.setMappingId(UUID.randomUUID());
            return saved;
        });

        chart.createStatementLine(TEMPLATE_LINE, ACCOUNT_ID);

        ArgumentCaptor<StatementLineMapping> saved = ArgumentCaptor.forClass(StatementLineMapping.class);
        org.mockito.Mockito.verify(statementLines).save(saved.capture());
        assertThat(saved.getValue().getAccountName()).isEqualTo("Service Revenue");
        assertThat(saved.getValue().getLocationId()).isNull();
        assertThat(saved.getValue().getStatementLineCode()).isEqualTo("REVENUE");
    }

    private StatementLineMapping line(String locationId) {
        return StatementLineMapping.builder()
                .glAccount(revenue)
                .accountName("4000")
                .statementType(StatementType.INCOME_STATEMENT)
                .statementLineCode("REVENUE")
                .lineDescription("REVENUE")
                .displayOrder(1)
                .operation(OperationType.SUM)
                .locationId(locationId)
                .build();
    }

    private static GLAccount account() {
        GLAccount account = new GLAccount(ACCOUNT_ID);
        account.setAccountCode("4000");
        account.setAccountName("Service Revenue");
        account.setAccountType(AccountType.REVENUE);
        return account;
    }
}
