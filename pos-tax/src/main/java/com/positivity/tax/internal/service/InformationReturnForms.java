package com.positivity.tax.internal.service;

import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.config.TaxProperties.InformationReturnBox;
import com.positivity.tax.internal.config.TaxProperties.InformationReturnCountry;
import com.positivity.tax.internal.config.TaxProperties.InformationReturnForm;
import com.positivity.tax.internal.dto.InformationReturnFormsResponse;
import com.positivity.tax.internal.dto.InformationReturnFormsResponse.BoxEntry;
import com.positivity.tax.internal.dto.InformationReturnFormsResponse.FormEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The information-return forms stub (CAP:550 #2615, AW48): per country, which information-return forms
 * exist, their boxes and the payee-id schemes a payee may be reported under. Built once from
 * {@code pos.tax.information-returns}; the constructor is the startup check, so a bad entry stops the
 * service with an {@link IllegalStateException} naming the offending property.
 * <p>
 * Nothing here names a country, form, box or scheme: every value is configuration held for expert advice
 * (OI-4), and the shipped rows are placeholders. Country codes are map keys and must be upper case (relaxed
 * binding lower-cases an environment-variable key, and this check then refuses it); forms and boxes are
 * lists with explicit codes, so binding never mangles a {@code _}.
 * <p>
 * The startup check refuses: a country key that is not an upper-case ISO 3166-1 alpha-2 code (assigned or
 * user-assigned, so fixtures may use {@code ZZ}); a form code outside {@code ^[A-Z][A-Z0-9_]{0,31}$} or
 * repeated in a country; a form without a box; a box code outside {@code ^[A-Z0-9]{1,10}$} or repeated in
 * a form; a blank label or one over 100 characters; a payee-id scheme outside {@code ^[A-Z][A-Z_]{1,15}$}
 * (#2623's code pattern) or repeated in a form. When #2623's scheme vocabulary lands, each scheme must also
 * be one of the country's; whichever lands later adds that check.
 */
@Component
public class InformationReturnForms {

    /** The answer's {@code source}: every value is a placeholder. */
    public static final String SOURCE = "STUB";

    private static final String PREFIX = "pos.tax.information-returns.";
    private static final Pattern ALPHA_2 = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern FORM_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,31}$");
    private static final Pattern BOX_CODE = Pattern.compile("^[A-Z0-9]{1,10}$");
    private static final Pattern SCHEME_CODE = Pattern.compile("^[A-Z][A-Z_]{1,15}$");
    private static final int MAX_LABEL = 100;

    /** ISO 3166-1 alpha-2: the assigned codes plus the user-assigned elements ISO reserves for private use. */
    private static final Set<String> ALPHA_2_CODES = alpha2Codes();

    private final Map<String, List<FormEntry>> forms;

    public InformationReturnForms(TaxProperties properties) {
        Map<String, List<FormEntry>> built = new LinkedHashMap<>();
        properties
                .getInformationReturns()
                .forEach((country, configured) -> built.put(country, validate(country, configured)));
        this.forms = Map.copyOf(built);
    }

    /**
     * The forms read: the forms {@code countryCode} configures.
     *
     * @param countryCode an upper-case ISO 3166-1 alpha-2 code
     * @return the forms, empty for a country without any, with {@code source = STUB}
     */
    @NonNull
    public InformationReturnFormsResponse read(@NonNull String countryCode) {
        return new InformationReturnFormsResponse(countryCode, SOURCE, forms.getOrDefault(countryCode, List.of()));
    }

    // ---------------------------------------------------------------------------------------
    // Startup check
    // ---------------------------------------------------------------------------------------

    private static List<FormEntry> validate(String country, @Nullable InformationReturnCountry configured) {
        String prefix = PREFIX + country;
        if (!ALPHA_2.matcher(country).matches() || !ALPHA_2_CODES.contains(country)) {
            throw invalid(prefix, "the country code is not an upper-case ISO 3166-1 alpha-2 code");
        }
        List<InformationReturnForm> source = configured == null ? List.of() : configured.getForms();
        List<FormEntry> entries = new ArrayList<>();
        Set<String> formCodes = new HashSet<>();
        for (int i = 0; i < source.size(); i++) {
            String formPrefix = prefix + ".forms[" + i + "]";
            InformationReturnForm form = source.get(i);
            if (form == null) {
                throw invalid(formPrefix, "the form is empty");
            }
            String code = form.getCode();
            if (code == null || !FORM_CODE.matcher(code).matches()) {
                throw invalid(formPrefix + ".code", "must match " + FORM_CODE.pattern());
            }
            if (!formCodes.add(code)) {
                throw invalid(formPrefix + ".code", "repeats form " + code + " in the country");
            }
            String label = label(formPrefix + ".label", form.getLabel());
            entries.add(new FormEntry(
                    code, label, boxes(formPrefix, form.getBoxes()), schemes(formPrefix, form.getPayeeIdSchemes())));
        }
        return List.copyOf(entries);
    }

    private static List<BoxEntry> boxes(String formPrefix, @Nullable List<InformationReturnBox> boxes) {
        if (boxes == null || boxes.isEmpty()) {
            throw invalid(formPrefix + ".boxes", "a form needs at least one box");
        }
        List<BoxEntry> entries = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (int j = 0; j < boxes.size(); j++) {
            String boxPrefix = formPrefix + ".boxes[" + j + "]";
            InformationReturnBox box = boxes.get(j);
            String code = box == null ? null : box.getCode();
            if (code == null || !BOX_CODE.matcher(code).matches()) {
                throw invalid(boxPrefix + ".code", "must match " + BOX_CODE.pattern());
            }
            if (!codes.add(code)) {
                throw invalid(boxPrefix + ".code", "repeats box " + code + " in the form");
            }
            entries.add(new BoxEntry(code, label(boxPrefix + ".label", box.getLabel())));
        }
        return List.copyOf(entries);
    }

    private static List<String> schemes(String formPrefix, @Nullable List<String> schemes) {
        List<String> source = schemes == null ? List.of() : schemes;
        Set<String> seen = new HashSet<>();
        for (int k = 0; k < source.size(); k++) {
            String scheme = source.get(k);
            String property = formPrefix + ".payee-id-schemes[" + k + "]";
            if (scheme == null || !SCHEME_CODE.matcher(scheme).matches()) {
                throw invalid(property, "must match " + SCHEME_CODE.pattern());
            }
            if (!seen.add(scheme)) {
                throw invalid(property, "repeats scheme " + scheme + " in the form");
            }
        }
        return List.copyOf(source);
    }

    private static String label(String property, @Nullable String label) {
        if (label == null || label.isBlank() || label.length() > MAX_LABEL) {
            throw invalid(property, "must be 1-" + MAX_LABEL + " characters");
        }
        return label;
    }

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

    private static IllegalStateException invalid(String property, String reason) {
        return new IllegalStateException("Invalid tax configuration " + property + ": " + reason);
    }
}
