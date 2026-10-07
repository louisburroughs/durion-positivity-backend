package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.GLMapping;
import com.positivity.accounting.internal.entity.MappingKey;
import com.positivity.accounting.internal.entity.PostingCategory;
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
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** The reader's contract (#2526): read under the platform binding, a stable fingerprint, empty is an error. */
@DisplayName("AccountingTemplateReader")
class AccountingTemplateReaderTest {

    private static final UUID SOME_TENANT = UUID.fromString("01900000-0000-7000-8000-0000000000aa");

    private final GLAccountRepository accounts = mock(GLAccountRepository.class);
    private final PostingCategoryRepository categories = mock(PostingCategoryRepository.class);
    private final MappingKeyRepository mappingKeys = mock(MappingKeyRepository.class);
    private final GLMappingRepository glMappings = mock(GLMappingRepository.class);
    private final DefaultGLMappingRepository defaultGlMappings = mock(DefaultGLMappingRepository.class);
    private final StatementLineMappingRepository statementLines = mock(StatementLineMappingRepository.class);
    private final List<Optional<UUID>> boundDuringRead = new ArrayList<>();

    private AccountingTemplateReader reader;

    @BeforeEach
    void setUp() {
        reader = newReader();
    }

    @AfterEach
    void clearBinding() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("reads the template under the platform binding and restores the caller's binding")
    void readsUnderThePlatformBinding() {
        seedPlatformRows(false);
        TenantContext.bind(SOME_TENANT);

        AccountingTemplate template = reader.snapshot();

        assertThat(boundDuringRead).isNotEmpty().containsOnly(Optional.of(PlatformTenant.ID));
        assertThat(TenantContext.current()).contains(SOME_TENANT);
        assertThat(template.entries())
                .extracting(AccountingTemplate.Entry::entryKey)
                .containsExactly(
                        "ACCOUNT:4000",
                        "ACCOUNT:6350",
                        "CATEGORY:INVOICE_REVENUE",
                        "MAPPING_KEY:INVOICE_REVENUE/SERVICE_REVENUE",
                        "GL_MAPPING:INVOICE_REVENUE/SERVICE_REVENUE",
                        "STATEMENT_LINE:INCOME_STATEMENT:4000",
                        "STATEMENT_LINE:LABOR_OVERHEAD:6350");
        AccountingTemplate.GlMapping mapping =
                (AccountingTemplate.GlMapping) template.entries().get(4);
        assertThat(mapping.accountCode()).as("references are by natural key").isEqualTo("4000");
    }

    @Test
    @DisplayName("the fingerprint does not depend on the order rows come back in, and moves when a value does")
    void fingerprintIsStable() {
        seedPlatformRows(false);
        String first = reader.snapshot().fingerprint();

        seedPlatformRows(true);
        assertThat(newReader().snapshot().fingerprint()).isEqualTo(first);

        GLAccount renamed = account("4000", "Sales", AccountType.REVENUE);
        when(accounts.findAll())
                .thenReturn(List.of(renamed, account("6350", "Retread Curing Consumables", AccountType.EXPENSE)));
        assertThat(newReader().snapshot().fingerprint()).isNotEqualTo(first);
    }

    @Test
    @DisplayName("an empty template is an error, is not kept, and the next use reads again")
    void emptyTemplateIsAnErrorAndIsRetried() {
        assertThatThrownBy(reader::snapshot).isInstanceOf(EmptyAccountingTemplateException.class);
        assertThat(reader.loaded()).isEmpty();

        seedPlatformRows(false);
        assertThat(reader.snapshot().isEmpty()).isFalse();
        assertThat(reader.loaded()).isPresent();
    }

    @Test
    @DisplayName("one successful read lasts the life of the process")
    void readsOnce() {
        seedPlatformRows(false);

        AccountingTemplate first = reader.snapshot();
        AccountingTemplate second = reader.snapshot();

        assertThat(second).isSameAs(first);
        verify(accounts, times(1)).findAll();
    }

    @Test
    @DisplayName("the generic source owns everything except the retread add-on's entries, for every tenant")
    void genericSourceExcludesTheRetreadAddOn() {
        seedPlatformRows(false);
        AccountingTemplate template = reader.snapshot();

        assertThat(reader.appliesTo(SOME_TENANT)).isTrue();
        assertThat(template.only(reader::owns).entries())
                .extracting(AccountingTemplate.Entry::entryKey)
                .doesNotContain("ACCOUNT:6350", "STATEMENT_LINE:LABOR_OVERHEAD:6350")
                .contains("ACCOUNT:4000", "STATEMENT_LINE:INCOME_STATEMENT:4000");
        assertThat(template.only(reader::owns).fingerprint()).isNotEqualTo(template.fingerprint());
    }

    private AccountingTemplateReader newReader() {
        return new AccountingTemplateReader(
                accounts,
                categories,
                mappingKeys,
                glMappings,
                defaultGlMappings,
                statementLines,
                mock(com.positivity.accounting.internal.repository.PettyExpenseCategoryRepository.class),
                mock(PlatformTransactionManager.class));
    }

    private void seedPlatformRows(boolean reversed) {
        GLAccount revenue = account("4000", "Service Revenue", AccountType.REVENUE);
        GLAccount curing = account("6350", "Retread Curing Consumables", AccountType.EXPENSE);
        PostingCategory category = new PostingCategory(UUID.randomUUID());
        category.setCategoryName("INVOICE_REVENUE");
        MappingKey key = new MappingKey(UUID.randomUUID());
        key.setPostingCategory(category);
        key.setKeyName("SERVICE_REVENUE");
        GLMapping mapping = new GLMapping();
        mapping.setPostingCategory(category);
        mapping.setMappingKey(key);
        mapping.setGlAccount(revenue);
        mapping.setSourceSystem("ACCOUNTING");
        mapping.setExternalCode("INVOICE_REVENUE_SERVICE_REVENUE");
        mapping.setEffectiveStartDate(LocalDateTime.of(2020, 1, 1, 0, 0));
        // A mapping with dimensions is not template data: the template maps each key once, plainly.
        GLMapping dimensioned = new GLMapping();
        dimensioned.setPostingCategory(category);
        dimensioned.setMappingKey(key);
        dimensioned.setGlAccount(curing);
        dimensioned.setSourceSystem("ACCOUNTING");
        dimensioned.setExternalCode("BY_LOCATION");
        dimensioned.setEffectiveStartDate(LocalDateTime.of(2020, 1, 1, 0, 0));
        dimensioned.setDimensions(Map.of("locationId", "L1"));
        StatementLineMapping revenueLine = StatementLineMapping.builder()
                .glAccount(revenue)
                .statementType(StatementType.INCOME_STATEMENT)
                .statementLineCode("REVENUE")
                .operation(OperationType.SUM)
                .build();
        StatementLineMapping curingLine = StatementLineMapping.builder()
                .glAccount(curing)
                .statementType(StatementType.LABOR_OVERHEAD)
                .statementLineCode("2.9.2")
                .parentLineCode("2.9")
                .operation(OperationType.SUM)
                .build();

        List<GLAccount> accountRows = reversed ? List.of(curing, revenue) : List.of(revenue, curing);
        List<StatementLineMapping> lineRows =
                reversed ? List.of(curingLine, revenueLine) : List.of(revenueLine, curingLine);
        when(accounts.findAll()).thenAnswer(invocation -> {
            boundDuringRead.add(TenantContext.current());
            return accountRows;
        });
        when(categories.findAll()).thenAnswer(invocation -> {
            boundDuringRead.add(TenantContext.current());
            return List.of(category);
        });
        when(mappingKeys.findAll()).thenReturn(List.of(key));
        when(glMappings.findAll()).thenReturn(List.of(mapping, dimensioned));
        when(defaultGlMappings.findAll()).thenReturn(List.of());
        when(statementLines.findAll()).thenAnswer(invocation -> {
            boundDuringRead.add(TenantContext.current());
            return lineRows;
        });
    }

    private static GLAccount account(String code, String name, AccountType type) {
        GLAccount account = new GLAccount(UUID.randomUUID());
        account.setAccountCode(code);
        account.setAccountName(name);
        account.setAccountType(type);
        account.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));
        return account;
    }
}
