package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.enums.StatementType;
import com.positivity.accounting.internal.enums.TemplateEntryKind;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * The retread-plant add-on (AW30; SPEC-accounting-workspace §4.6 "Retread add-on"): the accounts
 * only a shop that runs a retread plant needs, and their Labor &amp; Overhead lines.
 *
 * <p>It is held in the platform tenant like the rest of the template and kept out of the generic
 * source by entry key. A tenant receives it only once a CONTROLLER or ADMIN has chosen it at tenant
 * setup: the choice is the {@value #CONFIG_KEY} row of {@code accounting_configuration}, and a
 * missing row means off, so the add-on is never a default. There is no switching off: nothing the
 * template provisioned is ever removed. The alpha default tenant's choice is recorded on by
 * {@code V5__tenant_template_provisioning.sql}, because {@code V2__seed_accounting.sql} already gave
 * it these accounts.
 *
 * <p>The codes are V2's as renumbered by AW30 (#2511, {@code V11__chart_float_petty_expense_categories.sql}):
 * 4940 Rubber Dust Sales Income was 6900, a revenue account in the expense range.
 */
@Component
@RequiredArgsConstructor
public class RetreadPlantAddOnSource implements AccountingTemplateSource {

    /** The {@code accounting_configuration} key holding a tenant's choice. */
    public static final String CONFIG_KEY = "RETREAD_PLANT_ADD_ON";

    /** The stored value of a choice that was made. */
    public static final String ON = "true";

    /** The add-on's account codes. */
    public static final List<String> ACCOUNT_CODES = List.of("6350", "6450", "6470", "6510", "6520", "6530", "4940");

    /** The entry keys the add-on owns: its accounts and their Labor &amp; Overhead lines. */
    public static final Set<String> ENTRY_KEYS = entryKeys();

    private final AccountingConfigurationRepository configuration;

    @Override
    public String name() {
        return "retread-plant";
    }

    @Override
    public boolean owns(AccountingTemplate.@NonNull Entry entry) {
        return ENTRY_KEYS.contains(entry.entryKey());
    }

    @Override
    public boolean appliesTo(@NonNull UUID tenantId) {
        return configuration
                .findByConfigKey(CONFIG_KEY)
                .map(AccountingConfiguration::getConfigValue)
                .filter(ON::equals)
                .isPresent();
    }

    private static Set<String> entryKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (String code : ACCOUNT_CODES) {
            keys.add(AccountingTemplate.Account.keyOf(code));
            keys.add(TemplateEntryKind.STATEMENT_LINE.name() + ":" + StatementType.LABOR_OVERHEAD.name() + ":" + code);
        }
        return Set.copyOf(keys);
    }
}
