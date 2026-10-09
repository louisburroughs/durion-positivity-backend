package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.ExtInvoiceTax;
import com.positivity.accounting.internal.entity.GLMapping;
import com.positivity.accounting.internal.entity.InvoiceGlPosting;
import com.positivity.accounting.internal.entity.MappingKey;
import com.positivity.accounting.internal.entity.PostingCategory;
import com.positivity.accounting.internal.repository.ExtInvoiceTaxRepository;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.repository.InvoiceGlPostingRepository;
import com.positivity.accounting.internal.repository.MappingKeyRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Output tax by tax type (CAP:550 S32d item 11; AW50; SPEC-accounting-workspace §4.7, §9.5a).
 *
 * <p><b>Who posts typed.</b> A tenant whose {@code INVOICE_REVENUE} posting category holds any mapping key named
 * {@value #TYPED_KEY_PREFIX}{@code <taxType>} with a GL mapping effective on the posting date (provisioned by its
 * currency template data) posts one tax leg per tax type, each to that type's key. Every other tenant (every USD tenant
 * today) has no such key and posts its tax to {@value #UNTYPED_KEY} exactly as before. The tax types themselves are
 * data: the type on each {@code ext_invoice_tax} row (pos-tax's, S32a) and the key names the template provisions.
 * Nothing here names a tax type, a regime or an account.
 *
 * <p><b>Untyped tax (AW50).</b> For a typed tenant, an invoice whose typed rows do not account for its whole tax, or
 * whose tax type has no mapped key, has no {@link Plan.Typed} plan: the caller posts nothing and holds the fact. No
 * default account is used and no type is inferred.
 *
 * <p><b>Amounts</b> (ADR-0067 PC-6, OP-11). Each type's amount is the sum of its rows, rounded HALF_UP at the ledger
 * currency's exponent. The rows account for the invoice's tax when their rounded sum equals it; a cent the per-type
 * rounding leaves over or short goes on the largest leg (the first in tax-type order on a tie), so the legs always sum
 * exactly to the stated tax and the entry balances.
 */
@Component
@RequiredArgsConstructor
public class TypedOutputTax {

    /** The posting category output tax posts under. */
    public static final String POSTING_CATEGORY = "INVOICE_REVENUE";

    /** The untyped tax-payable key every tenant has. */
    public static final String UNTYPED_KEY = "SALES_TAX_PAYABLE";

    /** The prefix of a typed tax-payable key: {@code SALES_TAX_PAYABLE_<taxType>}. */
    public static final String TYPED_KEY_PREFIX = UNTYPED_KEY + "_";

    private final PostingCategoryRepository postingCategories;
    private final MappingKeyRepository mappingKeys;
    private final GLMappingRepository glMappings;
    private final ExtInvoiceTaxRepository invoiceTaxRows;
    private final InvoiceGlPostingRepository invoiceGlPostings;
    private final LedgerCurrency ledgerCurrency;

    /** How an invoice's tax posts. */
    public sealed interface Plan {

        /** The tenant has no typed key: one leg to {@value #UNTYPED_KEY}, as before S32d. */
        record Untyped() implements Plan {}

        /** One leg per tax type, summing exactly to the tax; empty when there is no tax. */
        record Typed(@NonNull List<Leg> legs) implements Plan {}

        /** A typed tenant whose tax cannot be posted by type (AW50): nothing posts, the fact is held. */
        record TaxTypeMissing(@NonNull String detail) implements Plan {}
    }

    /**
     * One typed tax leg.
     *
     * @param taxType the tax type
     * @param accountId the account its key maps to on the posting date
     * @param amount the amount, at the currency's exponent
     */
    public record Leg(
            @NonNull String taxType,
            @NonNull UUID accountId,
            @NonNull BigDecimal amount) {}

    /**
     * How the whole tax of {@code invoiceId} posts on {@code postingDate}.
     *
     * @param tax the invoice's stated tax (the fact's {@code tax}); null is zero
     */
    public @NonNull Plan planInvoice(@NonNull UUID invoiceId, BigDecimal tax, @NonNull LocalDateTime postingDate) {
        Map<String, UUID> accounts = typedAccounts(postingDate);
        if (accounts.isEmpty()) {
            return new Plan.Untyped();
        }
        BigDecimal stated = scaled(tax == null ? BigDecimal.ZERO : tax);
        if (stated.signum() == 0) {
            return new Plan.Typed(List.of());
        }
        Rows rows = rows(invoiceId);
        if (rows.untyped().signum() != 0) {
            return new Plan.TaxTypeMissing(
                    "Invoice " + invoiceId + " has tax of " + rows.untyped().toPlainString()
                            + " on rows without a tax type; no default account is used and no type is inferred (AW50)");
        }
        BigDecimal typed = scaled(rows.byType().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        if (typed.compareTo(stated) != 0) {
            return new Plan.TaxTypeMissing("Invoice " + invoiceId + "'s typed tax rows account for "
                    + typed.toPlainString() + " of its tax of " + stated.toPlainString() + " (AW50)");
        }
        return legs(invoiceId, roundToTotal(rows.byType(), stated), accounts);
    }

    /**
     * How {@code taxReversed} of a credit against {@code invoiceId} reverses on {@code postingDate} (Accounting ruling
     * R3.1 on #2639): it follows how the invoice's own {@code INVOICE_REVENUE} entry posted its tax, never the tenant's
     * keys today. An invoice posted untyped is credited untyped. An invoice posted by type is credited by type, split
     * over its typed tax by {@link TaxCreditAllocator} (rounded at the currency's exponent, residual on the largest
     * type), so a credit reverses each type in the share it was collected. An invoice without a posted entry, for a
     * tenant that posts by type, is held (AW50): the credit waits too ({@link Plan.TaxTypeMissing}).
     */
    public @NonNull Plan planCredit(
            @NonNull UUID invoiceId, @NonNull BigDecimal taxReversed, @NonNull LocalDateTime postingDate) {
        Optional<InvoiceGlPosting> posting =
                invoiceGlPostings.findByInvoiceIdAndReversalJournalEntryIdIsNull(invoiceId);
        Map<String, UUID> accounts = typedAccounts(postingDate);
        if (posting.isEmpty()) {
            if (accounts.isEmpty()) {
                return new Plan.Untyped();
            }
            return new Plan.TaxTypeMissing("Invoice " + invoiceId + " has no posted revenue entry: its revenue waits"
                    + " until its tax is typed, and a credit against it waits too (AW50)");
        }
        if (!posting.get().isTaxPostedByType()) {
            return new Plan.Untyped();
        }
        BigDecimal reversed = scaled(taxReversed);
        if (reversed.signum() == 0) {
            return new Plan.Typed(List.of());
        }
        Rows rows = rows(invoiceId);
        if (rows.untyped().signum() != 0 || rows.byType().isEmpty()) {
            return new Plan.TaxTypeMissing("Invoice " + invoiceId + " has no tax by type to reverse "
                    + reversed.toPlainString() + " against; no default account is used and no type is inferred"
                    + " (AW50)");
        }
        Map<String, BigDecimal> weights = new TreeMap<>(rows.byType());
        weights.values().removeIf(weight -> weight.signum() <= 0);
        if (weights.isEmpty()) {
            return new Plan.TaxTypeMissing(
                    "Invoice " + invoiceId + " collected no typed tax to reverse " + reversed.toPlainString());
        }
        return legs(invoiceId, roundToTotal(TaxCreditAllocator.allocate(reversed, weights), reversed), accounts);
    }

    /**
     * The tenant's typed keys mapped on {@code postingDate}: tax type to account. Empty for a tenant without one,
     * which posts untyped.
     */
    public @NonNull Map<String, UUID> typedAccounts(@NonNull LocalDateTime postingDate) {
        Map<String, UUID> accounts = new TreeMap<>();
        Optional<PostingCategory> category = postingCategories.findByCategoryName(POSTING_CATEGORY);
        if (category.isEmpty()) {
            return accounts;
        }
        UUID categoryId = category.get().getPostingCategoryId();
        for (MappingKey key : mappingKeys.findByPostingCategory_PostingCategoryId(categoryId)) {
            String name = key.getKeyName();
            if (name == null || !name.startsWith(TYPED_KEY_PREFIX) || name.length() == TYPED_KEY_PREFIX.length()) {
                continue;
            }
            glMappings.findAllEffectiveMappings(categoryId, key.getMappingKeyId(), postingDate).stream()
                    .filter(TypedOutputTax::undimensioned)
                    .findFirst()
                    .ifPresent(mapping -> accounts.put(
                            name.substring(TYPED_KEY_PREFIX.length()),
                            mapping.getGlAccount().getGlAccountId()));
        }
        return accounts;
    }

    /**
     * The accounts every tax-payable key ({@value #UNTYPED_KEY} and each typed one) maps to at any time in {@code
     * [start, end]}: what the tax-liability reconciliation compares against, never a literal account code.
     */
    public @NonNull Set<UUID> taxPayableAccountsInForce(@NonNull LocalDateTime start, @NonNull LocalDateTime end) {
        Set<UUID> accounts = new LinkedHashSet<>();
        Optional<PostingCategory> category = postingCategories.findByCategoryName(POSTING_CATEGORY);
        if (category.isEmpty()) {
            return accounts;
        }
        UUID categoryId = category.get().getPostingCategoryId();
        for (MappingKey key : mappingKeys.findByPostingCategory_PostingCategoryId(categoryId)) {
            String name = key.getKeyName();
            if (name == null || !(name.equals(UNTYPED_KEY) || name.startsWith(TYPED_KEY_PREFIX))) {
                continue;
            }
            for (GLMapping mapping : glMappings.findByMappingKey_MappingKeyId(key.getMappingKeyId())) {
                boolean startsInTime = !mapping.getEffectiveStartDate().isAfter(end);
                boolean endsAfterStart = mapping.getEffectiveEndDate() == null
                        || mapping.getEffectiveEndDate().isAfter(start);
                if (undimensioned(mapping) && startsInTime && endsAfterStart && mapping.getGlAccount() != null) {
                    accounts.add(mapping.getGlAccount().getGlAccountId());
                }
            }
        }
        return accounts;
    }

    private Plan legs(UUID invoiceId, Map<String, BigDecimal> amounts, Map<String, UUID> accounts) {
        List<Leg> legs = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> amount : amounts.entrySet()) {
            if (amount.getValue().signum() == 0) {
                continue;
            }
            UUID account = accounts.get(amount.getKey());
            if (account == null) {
                return new Plan.TaxTypeMissing("Invoice " + invoiceId + " has tax of type " + amount.getKey()
                        + " and the tenant maps no " + TYPED_KEY_PREFIX + amount.getKey() + " key under "
                        + POSTING_CATEGORY + "; no default account is used (AW50)");
            }
            legs.add(new Leg(amount.getKey(), account, amount.getValue()));
        }
        return new Plan.Typed(List.copyOf(legs));
    }

    /**
     * Each amount rounded HALF_UP at the currency's exponent, with what the rounding leaves over or short of {@code
     * total} on the largest amount (the first in key order on a tie), so the parts sum exactly to {@code total}.
     */
    private Map<String, BigDecimal> roundToTotal(Map<String, BigDecimal> amounts, BigDecimal total) {
        Map<String, BigDecimal> rounded = new TreeMap<>();
        String largest = null;
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> amount : new TreeMap<>(amounts).entrySet()) {
            BigDecimal value = scaled(amount.getValue());
            rounded.put(amount.getKey(), value);
            sum = sum.add(value);
            if (largest == null || value.compareTo(rounded.get(largest)) > 0) {
                largest = amount.getKey();
            }
        }
        BigDecimal residual = total.subtract(sum);
        if (largest != null && residual.signum() != 0) {
            rounded.put(largest, rounded.get(largest).add(residual));
        }
        return rounded;
    }

    private Rows rows(UUID invoiceId) {
        Map<String, BigDecimal> byType = new TreeMap<>();
        BigDecimal untyped = BigDecimal.ZERO;
        for (ExtInvoiceTax row : invoiceTaxRows.findByInvoiceId(invoiceId)) {
            BigDecimal amount = row.getTaxAmount() == null ? BigDecimal.ZERO : row.getTaxAmount();
            if (amount.signum() == 0) {
                continue;
            }
            if (row.getTaxType() == null || row.getTaxType().isBlank()) {
                untyped = untyped.add(amount);
            } else {
                byType.merge(row.getTaxType(), amount, BigDecimal::add);
            }
        }
        return new Rows(byType, untyped);
    }

    private BigDecimal scaled(BigDecimal amount) {
        int digits = Math.max(0, Currency.getInstance(ledgerCurrency.code()).getDefaultFractionDigits());
        return amount.setScale(digits, RoundingMode.HALF_UP);
    }

    private static boolean undimensioned(GLMapping mapping) {
        return mapping.getDimensions() == null || mapping.getDimensions().isEmpty();
    }

    private record Rows(Map<String, BigDecimal> byType, BigDecimal untyped) {}
}
