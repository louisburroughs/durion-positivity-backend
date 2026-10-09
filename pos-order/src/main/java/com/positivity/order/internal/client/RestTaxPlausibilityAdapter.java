package com.positivity.order.internal.client;

import com.positivity.shared.error.ApiError;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * REST adapter on pos-tax's plausibility check and evidence-rules read (CAP:550 S32d). It calls pos-tax with the
 * service authority through the gateway headers, as {@link RestTaxPortAdapter} does, forwards the inbound {@code
 * X-Correlation-Id}, and uses the {@code taxCheckRestClient} with its explicit timeouts.
 *
 * <p>Nothing here logs a request or response body: the request carries the supplier's number, which is INTERNAL
 * (ADR-0072 Decision 1). A failure is logged by its kind only.
 */
@Component
@Slf4j
public class RestTaxPlausibilityAdapter implements TaxPlausibilityPort {

    static final String PLAUSIBILITY_PATH = "/v1/tax/plausibility-checks";
    static final String EVIDENCE_RULES_PATH = "/v1/tax/evidence-rules";
    static final String CORRELATION_HEADER = "X-Correlation-Id";
    static final String IMPLAUSIBLE = "TAX_AMOUNT_IMPLAUSIBLE";
    private static final String SUPPLIER_RULE = "SUPPLIER_REGISTRATION_NUMBER";
    private static final String DRAWER_RECEIPT = "DRAWER_RECEIPT";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public RestTaxPlausibilityAdapter(
            @Qualifier("taxCheckRestClient") RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public @NonNull PlausibilityAnswer check(@NonNull PlausibilityQuery query) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("countryCode", query.countryCode());
        body.put("regionCode", query.regionCode());
        body.put("postalCode", query.postalCode());
        body.put("city", query.city());
        body.put("asOf", query.asOf().toString());
        body.put("currencyCode", query.currencyCode());
        body.put("receiptTotal", query.receiptTotal());
        body.put(
                "statedTaxes",
                query.statedTaxes().stream()
                        .map(tax -> Map.of("regime", tax.regime(), "amount", tax.amount()))
                        .toList());
        body.put("supplierRegistrationNumber", query.supplierRegistrationNumber());
        try {
            RestClient.RequestBodySpec request = restClient
                    .post()
                    .uri(PLAUSIBILITY_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON);
            withServiceHeaders(request);
            return request.body(body).exchange((_, response) -> {
                int status = response.getStatusCode().value();
                JsonNode answer = read(response.getBody());
                return classify(status, answer);
            });
        } catch (RuntimeException e) {
            log.warn("pos-tax plausibility check unavailable: {}", e.getClass().getSimpleName());
            return new Unavailable();
        }
    }

    @Override
    public @NonNull Optional<EvidenceThreshold> drawerEvidenceRule(
            @NonNull String countryCode, @NonNull LocalDate asOf) {
        try {
            RestClient.RequestHeadersSpec<?> request = restClient
                    .get()
                    .uri(uri -> uri.path(EVIDENCE_RULES_PATH)
                            .queryParam("countryCode", countryCode)
                            .queryParam("asOf", asOf.toString())
                            .build())
                    .accept(MediaType.APPLICATION_JSON);
            withServiceHeaders(request);
            return request.exchange((_, response) -> {
                if (response.getStatusCode().value() != 200) {
                    log.warn(
                            "pos-tax evidence-rules read answered {}",
                            response.getStatusCode().value());
                    return Optional.<EvidenceThreshold>empty();
                }
                return threshold(read(response.getBody()));
            });
        } catch (RuntimeException e) {
            log.warn("pos-tax evidence-rules read unavailable: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void withServiceHeaders(RestClient.RequestHeadersSpec<?> request) {
        request.header("X-User", "pos-order").header("X-Authorities", "tax:rates:view");
        String correlationId = correlationId();
        if (correlationId != null) {
            request.header(CORRELATION_HEADER, correlationId);
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

    private @Nullable JsonNode read(InputStream body) {
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** pos-tax's answer by its status and, for a refusal, its {@code code}. */
    static PlausibilityAnswer classify(int status, @Nullable JsonNode answer) {
        if (status == 200) {
            if (answer == null) {
                return new Unavailable();
            }
            String outcome = text(answer, "outcome");
            if (outcome == null) {
                return new Unavailable();
            }
            JsonNode wellFormed = answer.path("supplierRegistrationNumberWellFormed");
            return new Checked(
                    outcome,
                    answer.path("supplierRegistrationRequired").asBoolean(false),
                    wellFormed.isBoolean() ? wellFormed.booleanValue() : null);
        }
        if (status >= 400 && status < 500) {
            String code = answer == null ? null : text(answer, "code");
            if (status == 422 && IMPLAUSIBLE.equals(code)) {
                String message = text(answer, "message");
                return new Implausible(
                        message == null ? "A stated tax amount is implausible" : message, fieldErrors(answer));
            }
            return new Disagreement(status, code);
        }
        return new Unavailable();
    }

    private static List<ApiError.FieldError> fieldErrors(JsonNode answer) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        for (JsonNode error : answer.path("fieldErrors")) {
            String field = text(error, "field");
            if (field != null) {
                String message = text(error, "message");
                errors.add(new ApiError.FieldError(field, message == null ? "" : message));
            }
        }
        return errors;
    }

    private static Optional<EvidenceThreshold> threshold(@Nullable JsonNode answer) {
        if (answer == null) {
            return Optional.empty();
        }
        String currency = text(answer, "currency");
        if (currency == null) {
            return Optional.empty();
        }
        for (JsonNode rule : answer.path("rules")) {
            boolean drawer = false;
            for (JsonNode type : rule.path("appliesTo")) {
                drawer |= DRAWER_RECEIPT.equals(type.asString(""));
            }
            JsonNode fromAmount = rule.path("fromAmount");
            if (drawer && SUPPLIER_RULE.equals(text(rule, "rule")) && fromAmount.isNumber()) {
                return Optional.of(new EvidenceThreshold(fromAmount.decimalValue(), currency));
            }
        }
        return Optional.empty();
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asString("");
        return text.isBlank() ? null : text;
    }
}
