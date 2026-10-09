package com.positivity.accounting.internal.config;

import java.util.Arrays;
import java.util.Currency;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The tenant's tax country (CAP:550 #2615): the one place pos-accounting reads {@code accounting.tax.country}, the ISO
 * 3166-1 alpha-2 country whose tax configuration in pos-tax applies, such as its information-return forms. It follows
 * {@link LedgerCurrency}: a Stage A, deployment-wide value that ADR-0067 step A5's tenant replica replaces, together
 * with the ledger currency. No code branches on its value; it is only passed to pos-tax.
 *
 * <p>The startup check: the value is an upper-case ISO 3166-1 alpha-2 code, assigned or user-assigned (AA, QM-QZ,
 * XA-XZ, ZZ, so a fixture may use {@code ZZ}); and when the JDK maps the country to a currency, that currency is the
 * ledger's (ADR-0067 PC-9: one ledger currency in Stage A). Otherwise startup fails and the message names the
 * properties at fault.
 */
@Component
public class TaxCountry {

    static final String PROPERTY = "accounting.tax.country";
    static final String LEDGER_PROPERTY = "accounting.ledger.base-currency";

    private static final Pattern ALPHA_2 = Pattern.compile("^[A-Z]{2}$");
    private static final Set<String> ALPHA_2_CODES = alpha2Codes();

    private final String code;

    public TaxCountry(@Value("${" + PROPERTY + "}") @Nullable String code, @NonNull LedgerCurrency ledgerCurrency) {
        if (code == null || !ALPHA_2.matcher(code).matches() || !ALPHA_2_CODES.contains(code)) {
            throw new IllegalStateException(
                    PROPERTY + " must be an upper-case ISO 3166-1 alpha-2 country code, was '" + code + "'");
        }
        String countryCurrency = currencyOf(code);
        if (countryCurrency != null && !countryCurrency.equals(ledgerCurrency.code())) {
            throw new IllegalStateException(PROPERTY + "=" + code + " uses " + countryCurrency + ", but "
                    + LEDGER_PROPERTY + " is " + ledgerCurrency.code()
                    + "; a Stage A ledger books in one currency (ADR-0067 PC-9), so set both for the same country");
        }
        this.code = code;
    }

    /** The tax country's ISO 3166-1 alpha-2 code, upper case. */
    @NonNull
    public String code() {
        return code;
    }

    /** The JDK's currency of {@code country}, or null when it maps none (e.g. a user-assigned code). */
    private static @Nullable String currencyOf(String country) {
        try {
            Currency currency = Currency.getInstance(Locale.of("", country));
            return currency == null ? null : currency.getCurrencyCode();
        } catch (IllegalArgumentException noCurrency) {
            return null;
        }
    }

    /** ISO 3166-1 alpha-2: the assigned codes plus the user-assigned elements ISO reserves for private use. */
    private static Set<String> alpha2Codes() {
        Set<String> codes = new HashSet<>(Arrays.asList(Locale.getISOCountries()));
        codes.add("AA");
        codes.add("ZZ");
        for (char c = 'M'; c <= 'Z'; c++) {
            codes.add("Q" + c);
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            codes.add("X" + c);
        }
        return Set.copyOf(codes);
    }
}
