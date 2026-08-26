package com.positivity.mcp.internal.orchestration.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Facade over tax operations.
 *
 * <p>Direct-call exception (#641): the calculate leg injects the plain {@code @Primary} builder,
 * not {@code loadBalancedRestClientBuilder} — pos-tax is internal-only and sets
 * {@code register-with-eureka: false} (ADR-0021), so neither Eureka resolution nor the gateway
 * route can reach it. That base URL stays an explicit Docker DNS address.
 *
 * <p>Everything pos-tax does not serve goes through a second, load-balanced gateway client
 * ({@code pos.tax.gateway-base-url}): {@link #getTaxSummary} reads pos-accounting's tax-liability
 * report, and {@link #calculateTax}'s address lookup reads the location roster (#1519
 * WS-3.TAXCALC). The former {@code getTaxRate} tool is removed — pos-tax publishes no rate lookup
 * (#1522).
 */
@Component
public class TaxFacadeTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final RestClient gatewayRestClient;
    private final String taxCalculateUriTemplate;
    private final String locationUriTemplate;
    private final String taxSummaryUriTemplate;

    public TaxFacadeTool(
            RestClient.Builder restClientBuilder,
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${pos.tax.base-url}") @NonNull String baseUrl,
            @Value("${pos.tax.gateway-base-url}") @NonNull String gatewayBaseUrl,
            @Value("${pos.tax.tax-calculate-uri-template}") @NonNull String taxCalculateUriTemplate,
            @Value("${pos.tax.location-uri-template}") @NonNull String locationUriTemplate,
            @Value("${pos.tax.tax-summary-uri-template}") @NonNull String taxSummaryUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.gatewayRestClient =
                ToolRestClientSupport.instrumentedClient(loadBalancedRestClientBuilder, gatewayBaseUrl);
        this.taxCalculateUriTemplate = taxCalculateUriTemplate;
        this.locationUriTemplate = locationUriTemplate;
        this.taxSummaryUriTemplate = taxSummaryUriTemplate;
    }

    @Tool(
            description = "Calculate the estimated tax for a taxable amount at a location. amount must be a "
                    + "positive number; locationId is the location's id (UUID) — its address is looked up "
                    + "and used as the tax destination. Returns a JSON envelope with two sections: location "
                    + "(the location record whose address anchored the calculation) and tax (per-line and "
                    + "total tax amounts for one synthesized line item priced at the amount). If the "
                    + "location has no usable address (postal code and a 2-letter ISO country are required), "
                    + "the envelope is degraded with the reason and no tax is calculated.")
    public String calculateTax(
            @ToolParam(description = "The taxable amount (positive number)") @NonNull String amount,
            @ToolParam(description = "The location id (UUID)") @NonNull String locationId) {
        BigDecimal taxableAmount = parsePositiveAmount(amount);
        String locationBody;
        try {
            locationBody = gatewayRestClient
                    .get()
                    .uri(locationUriTemplate, Map.of("locationId", locationId))
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException locationFailure) {
            return ToolComposition.named("taxCalculation")
                    .call("location", () -> {
                        throw locationFailure;
                    })
                    .require("location")
                    .render();
        }
        JsonNode location = parseLocation(locationBody);
        String addressProblem = location == null
                ? "Location " + locationId + " returned an unreadable record; tax was not calculated"
                : destinationAddressProblem(location, locationId);
        String replayedLocationBody = locationBody;
        ToolComposition composition = ToolComposition.named("taxCalculation")
                .call("location", () -> replayedLocationBody)
                .require("location");
        if (addressProblem != null) {
            composition.call("tax", () -> {
                throw new ToolComposition.LegFailure(addressProblem);
            });
        } else {
            String requestBody = calculationRequest(taxableAmount, java.util.Objects.requireNonNull(location));
            composition.call(
                    "tax",
                    () -> restClient
                            .post()
                            .uri(taxCalculateUriTemplate)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(requestBody)
                            .retrieve()
                            .body(String.class));
        }
        return composition.require("tax").render();
    }

    @Tool(
            description = "Get the sales-tax liability summary for a reporting period: per-jurisdiction taxable "
                    + "base, exempt base, tax collected, credit reversals, and net tax. period must be a "
                    + "calendar month in YYYY-MM form (e.g. 2026-05) or a calendar year in YYYY form (e.g. "
                    + "2026); it is mapped onto the report's start/end date range.")
    public String getTaxSummary(
            @ToolParam(description = "Tax reporting period: YYYY-MM or YYYY") @NonNull String period) {
        ReportingPeriods.DateRange range = ReportingPeriods.toDateRange(period);
        return gatewayRestClient
                .get()
                .uri(taxSummaryUriTemplate, Map.of("startDate", range.startDate(), "endDate", range.endDate()))
                .retrieve()
                .body(String.class);
    }

    private static @NonNull BigDecimal parsePositiveAmount(@NonNull String amount) {
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(amount.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(
                    "amount '" + amount + "' is not a number: pass a positive decimal amount such as 129.99",
                    notANumber);
        }
        if (parsed.signum() <= 0) {
            throw new IllegalArgumentException("amount must be a positive number (tax on zero or negative "
                    + "amounts is not calculated); got '" + amount + "'");
        }
        return parsed;
    }

    /**
     * Reason the location cannot anchor a tax calculation, or {@code null} when its address is
     * usable. pos-tax requires {@code destinationAddress.countryCode} (ISO alpha-2) and
     * {@code postalCode}; the location's address fields are all optional.
     */
    private static @Nullable String destinationAddressProblem(@NonNull JsonNode location, @NonNull String locationId) {
        String country = location.path("country").asText("");
        String postalCode = location.path("postalCode").asText("");
        if (country.isBlank() || postalCode.isBlank()) {
            return "Location " + locationId + " has no usable address (postal code and country are "
                    + "required); tax was not calculated";
        }
        if (!country.matches("[A-Za-z]{2}")) {
            return "Location " + locationId + " has country '" + country + "', which is not a 2-letter "
                    + "ISO country code; tax was not calculated";
        }
        return null;
    }

    private static @Nullable JsonNode parseLocation(@Nullable String locationBody) {
        if (locationBody == null || locationBody.isBlank()) {
            return null;
        }
        try {
            JsonNode parsed = MAPPER.readTree(locationBody);
            return parsed.isObject() ? parsed : null;
        } catch (JsonProcessingException notJson) {
            return null;
        }
    }

    private static @NonNull String calculationRequest(@NonNull BigDecimal taxableAmount, @NonNull JsonNode location) {
        ObjectNode request = MAPPER.createObjectNode();
        ObjectNode lineItem = request.putArray("lineItems").addObject();
        lineItem.put("lineItemId", "1");
        lineItem.put("description", "Taxable amount");
        lineItem.put("quantity", 1);
        lineItem.put("unitPrice", taxableAmount);
        ObjectNode destination = request.putObject("destinationAddress");
        destination.put("countryCode", location.path("country").asText().toUpperCase(java.util.Locale.ROOT));
        destination.put("postalCode", location.path("postalCode").asText());
        putIfPresent(destination, "regionCode", location.path("state"));
        putIfPresent(destination, "city", location.path("city"));
        putIfPresent(destination, "line1", location.path("addressLine1"));
        try {
            return MAPPER.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException("Failed to render tax calculation request", exception);
        }
    }

    private static void putIfPresent(@NonNull ObjectNode destination, @NonNull String field, @NonNull JsonNode value) {
        String text = value.asText("");
        if (!text.isBlank()) {
            destination.put(field, text);
        }
    }
}
