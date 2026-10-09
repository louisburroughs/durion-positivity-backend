package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.enums.AccountType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CAP:550 S32d item 3 and AC 1 / AC 2: currency-conditional template data reaches only a tenant in that currency, and
 * the generic source never owns it. Fixture data only.
 */
@DisplayName("S32d currency-conditional template source")
class CurrencyTemplateSourceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final AccountingTemplate.Account RECOVERABLE =
            new AccountingTemplate.Account("1250", "GST/HST Recoverable", AccountType.ASSET, null, false, null, null);
    private static final AccountingTemplate.Account REVENUE =
            new AccountingTemplate.Account("4000", "Service Revenue", AccountType.REVENUE, null, false, null, null);

    private final AccountingTemplateReader reader = mock(AccountingTemplateReader.class);

    private CurrencyTemplateSource source(String functionalCurrency) {
        when(reader.currencyEntries()).thenReturn(Map.of("ACCOUNT:1250", "CAD"));
        return new CurrencyTemplateSource(reader, new LedgerCurrency(functionalCurrency));
    }

    @Test
    @DisplayName("AC 2: a CAD tenant receives the CAD entries")
    void cadTenantReceivesCadData() {
        CurrencyTemplateSource cad = source("CAD");

        assertThat(cad.appliesTo(TENANT)).isTrue();
        assertThat(cad.owns(RECOVERABLE)).isTrue();
        assertThat(cad.owns(REVENUE)).isFalse();
    }

    @Test
    @DisplayName("AC 1: a USD tenant receives none of it")
    void usdTenantReceivesNone() {
        CurrencyTemplateSource usd = source("USD");

        assertThat(usd.appliesTo(TENANT)).isFalse();
        assertThat(usd.owns(RECOVERABLE)).isFalse();
    }

    @Test
    @DisplayName("item 3: the snapshot a tenant applies is the generic part plus its currency's part")
    void templateForEachCurrency() {
        AccountingTemplate snapshot = AccountingTemplate.of(List.of(RECOVERABLE, REVENUE));
        when(reader.currencyEntries()).thenReturn(Map.of("ACCOUNT:1250", "CAD"));
        AccountingTemplateSource generic = new AccountingTemplateSource() {
            @Override
            public String name() {
                return "generic";
            }

            @Override
            public boolean owns(AccountingTemplate.Entry entry) {
                return !reader.currencyEntries().containsKey(entry.entryKey());
            }

            @Override
            public boolean appliesTo(UUID tenantId) {
                return true;
            }
        };

        for (String currency : List.of("CAD", "USD")) {
            CurrencyTemplateSource conditional = new CurrencyTemplateSource(reader, new LedgerCurrency(currency));
            AccountingTemplate applied = snapshot.only(
                    entry -> generic.owns(entry) || (conditional.appliesTo(TENANT) && conditional.owns(entry)));
            if ("CAD".equals(currency)) {
                assertThat(applied.entries()).containsExactlyInAnyOrder(RECOVERABLE, REVENUE);
            } else {
                assertThat(applied.entries()).containsExactly(REVENUE);
            }
        }
    }
}
