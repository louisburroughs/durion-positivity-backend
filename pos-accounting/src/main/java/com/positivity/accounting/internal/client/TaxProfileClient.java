package com.positivity.accounting.internal.client;

import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantHeaders;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * pos-accounting's reads of pos-tax's country profiles (CAP:550 S32d; ADR-0044 R2): the tax types, regimes and
 * currency a country configures (S32a, {@code GET /v1/tax/tax-types}) and the evidence rules in effect on a date
 * (S32b, {@code GET /v1/tax/evidence-rules}). Accounting reads them instead of naming any country, regime or tax
 * type in its own code.
 *
 * <p>pos-tax is internal-only and not on Eureka, so it is reached on {@code pos.accounting.tax.base-url} with the
 * gateway's authority header set by the caller ({@code tax:rates:view}), the bound tenant and the inbound {@code
 * X-Correlation-Id}, under the same connect and read timeouts as the registration front door.
 *
 * <p><b>An answer that cannot be obtained is never "off" (AW49).</b> Unreachable, a timeout, any non-2xx or an
 * unreadable body throws {@link TaxServiceUnavailableException}: a posting then rolls back for retry, and a request
 * answers 503 with {@code Retry-After}. Only the settings read treats it as "not known" ({@link #evidenceRules}'s
 * callers decide).
 */
@Slf4j
@Component
public class TaxProfileClient {

    static final String TAX_TYPES_PATH = "/v1/tax/tax-types";
    static final String EVIDENCE_RULES_PATH = "/v1/tax/evidence-rules";
    static final String AUTHORITY = "tax:rates:view";
    static final String SERVICE_USER = "pos-accounting";
    private static final String CORRELATION_HEADER = TaxRegistrationClient.CORRELATION_HEADER;

    private final RestClient restClient;

    /** The client Spring builds: its own request factory with the configured connect and read timeouts. */
    @Autowired
    public TaxProfileClient(
            RestClient.Builder restClientBuilder,
            @Value("${pos.accounting.tax.base-url:http://pos-tax:8091}") String baseUrl,
            @Value("${pos.accounting.tax.connect-timeout:2s}") Duration connectTimeout,
            @Value("${pos.accounting.tax.read-timeout:5s}") Duration readTimeout) {
        this(
                restClientBuilder
                        .clone()
                        .requestFactory(TaxRegistrationClient.requestFactory(connectTimeout, readTimeout)),
                baseUrl);
    }

    /** A client on a builder whose request factory the caller has set (tests bind a mock server to it). */
    TaxProfileClient(RestClient.Builder restClientBuilder, String baseUrl) {
        this.restClient = restClientBuilder.clone().baseUrl(baseUrl).build();
    }

    /**
     * The tax types, regimes and currency {@code countryCode} configures.
     *
     * @throws TaxServiceUnavailableException when pos-tax cannot answer
     */
    public @NonNull TaxTypes taxTypes(@NonNull String countryCode) {
        TaxTypes body = get(TAX_TYPES_PATH + "?countryCode={countryCode}", TaxTypes.class, "tax types", countryCode);
        return new TaxTypes(
                body.countryCode() == null ? countryCode : body.countryCode(),
                body.currency(),
                body.taxTypes() == null ? List.of() : List.copyOf(body.taxTypes()),
                body.regimes() == null ? List.of() : List.copyOf(body.regimes()));
    }

    /**
     * The evidence rules {@code countryCode} configures in effect on {@code asOf}.
     *
     * @throws TaxServiceUnavailableException when pos-tax cannot answer
     */
    public @NonNull EvidenceRules evidenceRules(@NonNull String countryCode, @NonNull LocalDate asOf) {
        EvidenceRules body = get(
                EVIDENCE_RULES_PATH + "?countryCode={countryCode}&asOf={asOf}",
                EvidenceRules.class,
                "evidence rules",
                countryCode,
                asOf);
        return new EvidenceRules(
                body.countryCode() == null ? countryCode : body.countryCode(),
                body.asOf() == null ? asOf : body.asOf(),
                body.currency(),
                body.rules() == null ? List.of() : List.copyOf(body.rules()),
                body.supplierRegistrationRegime());
    }

    private <T> T get(String uri, Class<T> type, String what, Object... variables) {
        UUID tenant = TenantContext.require();
        try {
            RestClient.RequestHeadersSpec<?> request = restClient
                    .get()
                    .uri(uri, variables)
                    .header("X-User", SERVICE_USER)
                    .header("X-Authorities", AUTHORITY)
                    .header(TenantHeaders.HTTP_TENANT_ID, tenant.toString());
            String correlationId = correlationId();
            if (correlationId != null) {
                request = request.header(CORRELATION_HEADER, correlationId);
            }
            T body = request.retrieve().body(type);
            if (body == null) {
                throw new TaxServiceUnavailableException("The tax configuration returned no " + what);
            }
            return body;
        } catch (RestClientException e) {
            log.warn(
                    "pos-tax could not answer a {} read: {}", what, e.getClass().getSimpleName());
            throw new TaxServiceUnavailableException("The tax configuration is unavailable");
        }
    }

    /** The inbound request's correlation id, forwarded so pos-tax logs under the same id (ADR-0017 §4). */
    private static @Nullable String correlationId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String header = attributes.getRequest().getHeader(CORRELATION_HEADER);
            return header == null || header.isBlank() ? null : header.trim();
        }
        return null;
    }

    /**
     * One country's configured profile (S32a's {@code TaxTypesResponse}).
     *
     * @param countryCode the country
     * @param currency its ISO 4217 currency; null when pos-tax has no profile for it
     * @param taxTypes the tax types it declares
     * @param regimes the regimes it declares
     */
    public record TaxTypes(
            @NonNull String countryCode,
            @Nullable String currency,
            @NonNull List<TaxType> taxTypes,
            @NonNull List<Regime> regimes) {

        /** The declared tax type with this code. */
        public @NonNull Optional<TaxType> taxType(@NonNull String code) {
            return taxTypes.stream().filter(t -> code.equals(t.taxType())).findFirst();
        }

        /** Whether the country declares this regime. */
        public boolean declaresRegime(@NonNull String regime) {
            return regimes.stream().anyMatch(r -> regime.equals(r.regime()));
        }
    }

    /**
     * One declared tax type.
     *
     * @param taxType the code
     * @param regime the regime it is registered and recovered under; null for none
     * @param jurisdictionType the level it is levied at
     * @param inputTaxRecoverable the placeholder recoverability held for expert advice (OI-4)
     */
    public record TaxType(
            @NonNull String taxType,
            @Nullable String regime,
            @Nullable String jurisdictionType,
            boolean inputTaxRecoverable) {}

    /**
     * One declared regime.
     *
     * @param regime the regime
     * @param regions the regions it covers; empty for the whole country
     */
    public record Regime(@NonNull String regime, @Nullable List<String> regions) {}

    /**
     * The evidence rules in effect on a date (S32b's {@code TaxEvidenceRulesResponse}).
     *
     * @param countryCode the country
     * @param asOf the date
     * @param currency the currency of every amount; null with no profile
     * @param rules the rules in effect
     * @param supplierRegistrationRegime the regime whose registration a supplier's number is, when the country names
     *     one: what the {@code SUPPLIER_REGISTRATION_NUMBER} rule asks the vendor to hold (AW53)
     */
    public record EvidenceRules(
            @NonNull String countryCode,
            @NonNull LocalDate asOf,
            @Nullable String currency,
            @NonNull List<EvidenceRule> rules,
            @Nullable String supplierRegistrationRegime) {

        /** The lowest threshold of a rule that applies to {@code documentType}, if any. */
        public @NonNull Optional<EvidenceRule> forDocument(@NonNull String documentType) {
            return rules.stream()
                    .filter(rule -> rule.appliesTo() != null && rule.appliesTo().contains(documentType))
                    .filter(rule -> rule.fromAmount() != null)
                    .min((a, b) -> a.fromAmount().compareTo(b.fromAmount()));
        }
    }

    /**
     * One evidence rule.
     *
     * @param rule the evidence required, e.g. the supplier's registration number
     * @param fromAmount the document total, tax included, from which it applies (inclusive)
     * @param appliesTo the document types it applies to
     * @param effectiveFrom inclusive first date; null for always
     * @param effectiveTo inclusive last date; null for open-ended
     */
    public record EvidenceRule(
            @NonNull String rule,
            @Nullable BigDecimal fromAmount,
            @Nullable List<String> appliesTo,
            @Nullable LocalDate effectiveFrom,
            @Nullable LocalDate effectiveTo) {}
}
