package com.positivity.tax.internal.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the tax service.
 */
@Data
@Component
@ConfigurationProperties(prefix = "pos.tax")
public class TaxProperties {

    /**
     * Test mode configuration.
     */
    private TestMode testMode = new TestMode();

    /**
     * Active external provider selection when test mode is disabled (story T6-external).
     * <p>
     * The {@code testMode.enabled} flag still wins: when it is {@code true} the internal
     * test calculator is always used regardless of this value. When test mode is disabled
     * this selects the concrete adapter — {@link Provider#AVALARA} for the real Avalara
     * AvaTax REST v2 adapter, otherwise the legacy generic {@code EXTERNAL} stub.
     * <p>
     * Defaults to {@link Provider#TEST_MODE}, which — when the test-mode flag is off —
     * resolves to the legacy external stub, preserving prior behavior when unset.
     */
    private Provider provider = Provider.TEST_MODE;

    /**
     * External tax service configuration.
     */
    private ExternalService externalService = new ExternalService();

    /**
     * Avalara AvaTax REST v2 adapter configuration (story T6-external, decision R-T1).
     */
    private Avalara avalara = new Avalara();

    /**
     * Concrete external tax provider (story T6-external).
     */
    public enum Provider {
        /**
         * No explicit external provider selected. When test mode is disabled this resolves to
         * the legacy {@link #EXTERNAL} stub, preserving prior behavior for existing deployments.
         */
        TEST_MODE,
        /** Real Avalara AvaTax REST v2 adapter. */
        AVALARA,
        /** Legacy generic HTTP external stub. */
        EXTERNAL
    }

    /**
     * Retry configuration for external service calls.
     */
    private Retry retry = new Retry();

    /**
     * Interim per-country default provider plug-in (ADR-0071 §3; CAP:550 S32a), keyed by ISO
     * 3166-1 alpha-2 country code, e.g. {@code XX: XX_SELF}.
     * <p>
     * An address whose country has an entry is answered by the named plug-in in every provider
     * mode; every other country keeps the deployment-wide switch ({@code test-mode.enabled},
     * {@link #provider}). The only plug-ins today are the configuration-driven self-hosted ones,
     * one per profiled country, named {@code <country>_SELF}. When the tenant binding of
     * ADR-0071 step 1 lands, a binding takes precedence and this map becomes the resolver's
     * fallback. Never {@code null}.
     */
    private Map<String, String> defaultProviders = new LinkedHashMap<>();

    /**
     * Per-country tax profiles (CAP:550 S32a), keyed by ISO 3166-1 alpha-2 country code.
     * <p>
     * Every value is a placeholder held for expert advice (spec AW48, OI-4): the tax types, their
     * regime grouping and recoverability, and the rates. Adding a country is configuration only.
     * Validated at startup by {@code TaxCountryProfiles}. Never {@code null}.
     */
    private Map<String, CountryProfile> countries = new LinkedHashMap<>();

    /**
     * Registration-number shapes (CAP:550 S32b). A shape is a security control (ADR-0072 Decision 1,
     * conditions (a) and (b)): it is the service's shipped configuration, changed only by a reviewed
     * commit, and never set per tenant, by a tenant, by a tax provider or at runtime.
     */
    private Registration registration = new Registration();

    /**
     * The stated-tax plausibility check (CAP:550 S32b, AW55): a bookkeeping control against typing
     * errors, not a tax rule.
     */
    private Plausibility plausibility = new Plausibility();

    /**
     * Information-return forms per country (CAP:550 #2615), keyed by upper-case ISO 3166-1 alpha-2 country
     * code. A configuration-driven stub (AW48): which forms exist in a country, their boxes and the
     * payee-id schemes a payee may be reported under are placeholders held for expert advice (OI-4).
     * Validated at startup by {@code InformationReturnForms}. Never {@code null}.
     */
    private Map<String, InformationReturnCountry> informationReturns = new LinkedHashMap<>();

    /**
     * One country's information-return configuration (CAP:550 #2615).
     */
    @Data
    public static class InformationReturnCountry {
        /**
         * The forms, each with an explicit {@code code} (a list, not a map: map keys lose their
         * underscores under relaxed binding). Never {@code null}.
         */
        private List<InformationReturnForm> forms = new ArrayList<>();
    }

    /**
     * One information-return form (CAP:550 #2615).
     */
    @Data
    public static class InformationReturnForm {
        /** The form code, {@code ^[A-Z][A-Z0-9_]{0,31}$}, unique in its country. */
        private String code;

        /** The form's label, 1-100 characters. */
        private String label;

        /** The form's boxes, at least one, each with an explicit {@code code}. Never {@code null}. */
        private List<InformationReturnBox> boxes = new ArrayList<>();

        /** The tax-registration schemes a payee may be reported under. Never {@code null}. */
        private List<String> payeeIdSchemes = new ArrayList<>();
    }

    /**
     * One box of an information-return form (CAP:550 #2615).
     */
    @Data
    public static class InformationReturnBox {
        /** The box code, {@code ^[A-Z0-9]{1,10}$}, unique in its form; quote it in YAML so {@code 020} stays text. */
        private String code;

        /** The box's label, 1-100 characters. */
        private String label;
    }

    /**
     * Registration-number configuration (CAP:550 S32b).
     */
    @Data
    public static class Registration {
        /**
         * One shape per regime, as a list with an explicit {@code regime} code (a map key would lose its
         * underscores under relaxed binding). Every regime a country profile declares needs one, or
         * startup fails. Never {@code null}.
         */
        private List<RegistrationFormat> formats = new ArrayList<>();
    }

    /**
     * The shape a regime's registration numbers must match (CAP:550 S32b).
     */
    @Data
    public static class RegistrationFormat {
        /** The regime code, as declared under {@code pos.tax.countries.<country>.regimes}. */
        private String regime;

        /**
         * A template: {@code #} stands for one digit and {@code A}-{@code Z} stand for themselves. It must
         * contain at least one letter, so no shape can match bare digits.
         */
        private String shape;
    }

    /**
     * Plausibility-check configuration (CAP:550 S32b).
     */
    @Data
    public static class Plausibility {
        /**
         * Minor units added to each stated amount's maximum. A placeholder held for expert advice; no
         * default in code, so startup fails when it is not configured.
         */
        private Integer toleranceMinorUnits;
    }

    /**
     * One country's tax profile (CAP:550 S32a).
     */
    @Data
    public static class CountryProfile {
        /** ISO 4217 currency of the country's tax amounts; its exponent sets the rounding scale. */
        private String currency;

        /**
         * Declared tax types, each with an explicit {@code code} (1-32 upper-case letters, digits or
         * underscores). A list, not a map: map keys lose their underscores under relaxed binding. The
         * vocabulary is configuration only. Never {@code null}.
         */
        private List<TaxTypeProfile> taxTypes = new ArrayList<>();

        /**
         * Registration and recovery regimes, each with an explicit {@code code}. Several tax types may
         * share one regime. Never {@code null}.
         */
        private List<RegimeProfile> regimes = new ArrayList<>();

        /** Effective-dated rate rows. None ship; tests and dev use fixtures. Never {@code null}. */
        private List<RateRow> rates = new ArrayList<>();

        /**
         * The regime whose shape a supplier's registration number must match (CAP:550 S32b); blank when
         * the country accepts no supplier number.
         */
        private String supplierRegistrationRegime;

        /**
         * Effective-dated evidence rules (CAP:550 S32b, AW53), in the profile's currency. Placeholders
         * held for expert advice. Never {@code null}.
         */
        private List<EvidenceRuleRow> evidenceRules = new ArrayList<>();
    }

    /**
     * An evidence rule: from which amount a document type needs a piece of evidence (CAP:550 S32b).
     */
    @Data
    public static class EvidenceRuleRow {
        /** The evidence required, an {@code EvidenceRule} code. */
        private String rule;

        /** The amount, tax included, from which the rule applies; must be above zero. */
        private BigDecimal fromAmount;

        /** The document types the rule applies to, {@code EvidenceDocumentType} codes; never empty. */
        private List<String> appliesTo = new ArrayList<>();

        /** Inclusive first date the rule is in effect; {@code null} for always. */
        private LocalDate effectiveFrom;

        /** Inclusive last date the rule is in effect; {@code null} for open-ended. */
        private LocalDate effectiveTo;
    }

    /**
     * A declared tax type of one country.
     */
    @Data
    public static class TaxTypeProfile {
        /** The tax-type code (1-32 upper-case letters, digits or underscores). */
        private String code;

        /** The regime this tax type is registered and recovered under; blank for none. */
        private String regime;

        /** The {@code TaxJurisdictionType} code the type is levied at (e.g. a country or a region). */
        private String jurisdictionType;

        /** Placeholder recoverability; required for every declared tax type. */
        private Boolean inputTaxRecoverable;
    }

    /**
     * A registration and recovery regime of one country.
     */
    @Data
    public static class RegimeProfile {
        /** The regime code. */
        private String code;

        /** Region codes the regime covers; empty means the whole country. Never {@code null}. */
        private List<String> regions = new ArrayList<>();
    }

    /**
     * An effective-dated rate row: one tax type in one region.
     */
    @Data
    public static class RateRow {
        /** Region (subdivision) code, 1–3 letters or digits. */
        private String regionCode;

        /** A tax type the country declares. */
        private String taxType;

        /** The rate as a decimal fraction in {@code [0, 1)}. */
        private BigDecimal rate;

        /** Inclusive first date the row is in effect. */
        private LocalDate effectiveFrom;

        /** Inclusive last date the row is in effect; {@code null} for open-ended. */
        private LocalDate effectiveTo;
    }

    @Data
    public static class TestMode {
        /**
         * Whether test mode is enabled.
         * <p>
         * When true, tax calculations use internal test logic instead of external service.
         */
        private boolean enabled = true;

        /**
         * Default tax rates by jurisdiction type.
         * <p>
         * Example: STATE=0.0725, COUNTY=0.01, CITY=0.0025
         */
        private Map<String, BigDecimal> defaultRates = new HashMap<>();

        /**
         * Effective-dated override schedule for test-mode rates.
         * <p>
         * Ordered list of {@link RateScheduleEntry}. When resolving rates for a
         * transaction, the entry with the greatest {@code effectiveFrom} that is not
         * after the transaction date is selected. When empty (the default) or when no
         * entry is effective on or before the transaction date, {@link #defaultRates}
         * is used, preserving prior behavior.
         * <p>
         * Never {@code null}; defaults to an empty list.
         */
        private List<RateScheduleEntry> rateSchedule = new ArrayList<>();

        /**
         * Address-driven, effective-dated jurisdiction rate rules (story T7).
         * <p>
         * Each rule matches on {@code destinationAddress} facets (state, city,
         * postal-code prefix) and carries its own rates and optional per-category
         * exemptions. For a given transaction the rule is selected from those that match
         * the address and whose {@code effectiveFrom} is not after the transaction date;
         * ties are broken by greatest {@code effectiveFrom}, then most-specific match, then
         * configured order. When no rule matches, resolution falls back to
         * {@link #rateSchedule} then {@link #defaultRates}, preserving prior behavior.
         * <p>
         * Never {@code null}; defaults to an empty list.
         */
        private List<JurisdictionRule> jurisdictions = new ArrayList<>();
    }

    /**
     * An address-driven, effective-dated jurisdiction rate rule (story T7).
     */
    @Data
    public static class JurisdictionRule {
        /**
         * Address facets this rule matches on. All populated facets must match.
         */
        private JurisdictionMatch match = new JurisdictionMatch();

        /**
         * Tax rates by jurisdiction type code for this rule (e.g. STATE=0.0625).
         */
        private Map<String, BigDecimal> rates = new HashMap<>();

        /**
         * Inclusive date on which this rule becomes effective. When {@code null} the rule is
         * treated as always effective.
         */
        private LocalDate effectiveFrom;

        /**
         * Tax categories that are exempt within this rule (story T7 category override).
         * <p>
         * A line whose {@code taxCategory} is listed here is treated as a (rule-based)
         * exempt line: it emits zero-rate jurisdiction rows and is excluded from the taxable
         * base — the minimal config-only sliver of a fiscal position (e.g. {@code LABOR}
         * exempt in some states). Matching is case-insensitive.
         */
        private Set<String> exemptCategories = new LinkedHashSet<>();
    }

    /**
     * Address facets a {@link JurisdictionRule} matches on. A {@code null}/blank facet is a
     * wildcard; a populated facet must equal (case-insensitively) the corresponding request
     * value, except {@code postalCodePrefix} which matches by prefix.
     */
    @Data
    public static class JurisdictionMatch {
        /** State/region subdivision code (e.g. CA, TX). */
        private String stateCode;

        /** City/locality name. */
        private String city;

        /** Postal-code prefix (matches when the request postal code starts with this value). */
        private String postalCodePrefix;
    }

    /**
     * A single effective-dated set of test-mode tax rates.
     * <p>
     * {@code effectiveFrom} is the inclusive start date on which {@code rates}
     * become applicable; an entry applies to any transaction date on or after it,
     * until a later entry supersedes it.
     */
    @Data
    public static class RateScheduleEntry {
        /**
         * Inclusive date on which this rate set becomes effective.
         */
        private LocalDate effectiveFrom;

        /**
         * Tax rates by jurisdiction type code for this effective period.
         * <p>
         * Example: STATE=0.0725, COUNTY=0.01, CITY=0.0025
         */
        private Map<String, BigDecimal> rates = new HashMap<>();
    }

    @Data
    public static class ExternalService {
        /**
         * Base URL for the external tax service API.
         */
        private String baseUrl = "https://api.taxservice.example.com";

        /**
         * API key for authentication.
         */
        private String apiKey;

        /**
         * Connection timeout in milliseconds.
         */
        private int connectTimeout = 5000;

        /**
         * Read timeout in milliseconds.
         */
        private int readTimeout = 10000;
    }

    /**
     * Avalara AvaTax REST v2 adapter configuration (story T6-external).
     * <p>
     * Authenticates with HTTP Basic ({@code accountId:licenseKey}); the license key is a
     * secret and is never logged. Env-var driven with empty defaults so the adapter is inert
     * until configured. Sandbox base URL is {@code https://sandbox-rest.avatax.com}; production
     * is {@code https://rest.avatax.com}.
     * <p>
     * <strong>D-T1 note (not built):</strong> Avalara CertCapture (ECM) could later become the
     * system of record for the T3 exemption-certificate registry. For now the in-platform
     * registry stays authoritative and no CertCapture integration exists in this story.
     */
    @Data
    public static class Avalara {
        /**
         * AvaTax REST base URL. Sandbox {@code https://sandbox-rest.avatax.com},
         * production {@code https://rest.avatax.com}.
         */
        private String baseUrl = "https://sandbox-rest.avatax.com";

        /**
         * Avalara account id (HTTP Basic username). Empty until configured.
         */
        private String accountId = "";

        /**
         * Avalara license key (HTTP Basic password) — secret, never logged. Empty until configured.
         */
        private String licenseKey = "";

        /**
         * AvaTax company code used as the document company on every transaction.
         */
        private String companyCode = "DEFAULT";

        /**
         * Connection timeout in milliseconds.
         */
        private int connectTimeout = 5000;

        /**
         * Read timeout in milliseconds.
         */
        private int readTimeout = 10000;
    }

    @Data
    public static class Retry {
        /**
         * Maximum number of retry attempts.
         */
        private int maxAttempts = 3;

        /**
         * Initial backoff duration in milliseconds.
         */
        private long initialBackoff = 500;

        /**
         * Backoff multiplier for exponential backoff.
         */
        private double multiplier = 2.0;
    }
}
