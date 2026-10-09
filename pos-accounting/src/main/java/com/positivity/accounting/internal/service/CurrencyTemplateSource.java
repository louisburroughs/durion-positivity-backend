package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * The currency-conditional part of the accounting template (CAP:550 S32d item 3; ADR-0067 OP-9): entries a tenant
 * receives only when its functional currency is the one the template data names, such as the recoverable and typed
 * payable tax accounts, their mapping keys and the categories' tax recovery a country's indirect tax needs.
 *
 * <p>The mechanism is generic: which entry belongs to which currency is template DATA, the platform tenant's {@code
 * accounting_template_currency_entry} rows written beside the entries by {@code R__seed_reference_accounting.sql}.
 * This class names no currency, account, regime or tax type. The generic source ({@link AccountingTemplateReader})
 * owns none of these entries, so a tenant in another currency, every USD tenant today, receives none of them.
 *
 * <p>The functional currency is the ledger currency until ADR-0067 A2 replicates a tenant's own ({@link
 * LedgerCurrency}); {@link #appliesTo} and {@link #owns} are then asked of the bound tenant's.
 */
@Component
@RequiredArgsConstructor
public class CurrencyTemplateSource implements AccountingTemplateSource {

    private final AccountingTemplateReader reader;
    private final LedgerCurrency ledgerCurrency;

    @Override
    public String name() {
        return "currency";
    }

    /** True for an entry the template data reserves for the functional currency. */
    @Override
    public boolean owns(AccountingTemplate.@NonNull Entry entry) {
        return ledgerCurrency.code().equals(reader.currencyEntries().get(entry.entryKey()));
    }

    /** True when the template holds data for the functional currency. */
    @Override
    public boolean appliesTo(@NonNull UUID tenantId) {
        return reader.currencyEntries().containsValue(ledgerCurrency.code());
    }
}
