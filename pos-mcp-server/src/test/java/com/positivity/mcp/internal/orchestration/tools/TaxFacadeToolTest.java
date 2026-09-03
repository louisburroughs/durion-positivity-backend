package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link TaxFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 * The calculate leg uses the ADR-0021 direct pos-tax client; the location lookup and the summary
 * use the gateway client.
 */
class TaxFacadeToolTest {

    private static final String DIRECT_BASE_URL = "http://pos-tax/v1/tax";
    private static final String GATEWAY_BASE_URL = "http://api-gateway";
    private static final String LOCATION_ID = "01960003-0000-7000-8000-0000000000d0";
    private static final String LOCATION_BODY = "{\"id\":\"" + LOCATION_ID
            + "\",\"name\":\"Downtown Service Center\",\"addressLine1\":\"123 Main St\","
            + "\"city\":\"Springfield\",\"state\":\"IL\",\"postalCode\":\"62704\",\"country\":\"US\"}";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockRestServiceServer directMockServer;
    private MockRestServiceServer gatewayMockServer;
    private TaxFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("TaxFacadeTool." + toolMethod);
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tool result is not valid JSON: " + json, exception);
        }
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder directBuilder = RestClient.builder();
        RestClient.Builder gatewayBuilder = RestClient.builder();
        directMockServer = MockRestServiceServer.bindTo(directBuilder).build();
        gatewayMockServer = MockRestServiceServer.bindTo(gatewayBuilder).build();
        FacadeContractManifest.Entry calculate = contract("calculateTax");
        tool = new TaxFacadeTool(
                directBuilder,
                gatewayBuilder,
                DIRECT_BASE_URL,
                GATEWAY_BASE_URL,
                calculate.leg("tax").template(),
                calculate.leg("location").template(),
                contract("getTaxSummary").template(),
                contract("getTaxRate").leg("rates").template());
    }

    @Test
    @DisplayName("calculateTax looks up the location and POSTs a nested destinationAddress to pos-tax")
    void calculateTax_postsSynthesizedLineItemWithDestinationAddress() {
        FacadeContractManifest.Entry calculate = contract("calculateTax");
        gatewayMockServer
                .expect(requestTo(
                        GATEWAY_BASE_URL + calculate.leg("location").expand(Map.of("locationId", LOCATION_ID))))
                .andExpect(method(calculate.leg("location").httpMethod()))
                .andRespond(withSuccess(LOCATION_BODY, MediaType.APPLICATION_JSON));
        directMockServer
                .expect(requestTo(DIRECT_BASE_URL + calculate.leg("tax").expand(Map.of())))
                .andExpect(method(calculate.leg("tax").httpMethod()))
                .andExpect(jsonPath("$.lineItems[0].lineItemId").value("1"))
                .andExpect(jsonPath("$.lineItems[0].quantity").value(1))
                .andExpect(jsonPath("$.lineItems[0].unitPrice").value(129.99))
                .andExpect(jsonPath("$.destinationAddress.countryCode").value("US"))
                .andExpect(jsonPath("$.destinationAddress.postalCode").value("62704"))
                .andExpect(jsonPath("$.destinationAddress.regionCode").value("IL"))
                .andExpect(jsonPath("$.destinationAddress.city").value("Springfield"))
                .andExpect(jsonPath("$.destinationAddress.line1").value("123 Main St"))
                // The legacy flat address fields must never be populated.
                .andExpect(jsonPath("$.address").doesNotExist())
                .andExpect(jsonPath("$.city").doesNotExist())
                .andExpect(jsonPath("$.countryCode").doesNotExist())
                .andExpect(jsonPath("$.postalCode").doesNotExist())
                .andRespond(withSuccess("{\"totalTax\":\"11.05\"}", MediaType.APPLICATION_JSON));

        JsonNode envelope = parse(tool.calculateTax("129.99", LOCATION_ID));

        directMockServer.verify();
        gatewayMockServer.verify();
        assertThat(envelope.get("composition").asText()).isEqualTo("taxCalculation");
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
        assertThat(envelope.get("sections")
                        .get("location")
                        .get("data")
                        .get("id")
                        .asText())
                .isEqualTo(LOCATION_ID);
        assertThat(envelope.get("sections")
                        .get("tax")
                        .get("data")
                        .get("totalTax")
                        .asText())
                .isEqualTo("11.05");
        assertThat(envelope.get("sources")).extracting(JsonNode::asText).containsExactly("location", "tax");
    }

    @Test
    @DisplayName("calculateTax degrades without calling pos-tax when the location has no usable address")
    void calculateTax_locationWithoutAddress_degradesWithoutCallingPosTax() {
        FacadeContractManifest.Entry calculate = contract("calculateTax");
        gatewayMockServer
                .expect(requestTo(
                        GATEWAY_BASE_URL + calculate.leg("location").expand(Map.of("locationId", LOCATION_ID))))
                .andRespond(withSuccess(
                        "{\"id\":\"" + LOCATION_ID + "\",\"name\":\"Warehouse\"}", MediaType.APPLICATION_JSON));

        JsonNode envelope = parse(tool.calculateTax("50", LOCATION_ID));

        directMockServer.verify();
        gatewayMockServer.verify();
        assertThat(envelope.get("status").asText()).isEqualTo("degraded");
        assertThat(envelope.get("sections").get("location").get("status").asText())
                .isEqualTo("ok");
        JsonNode tax = envelope.get("sections").get("tax");
        assertThat(tax.get("status").asText()).isEqualTo("error");
        assertThat(tax.get("reason").asText()).contains("no usable address");
        assertThat(envelope.get("sources")).extracting(JsonNode::asText).containsExactly("location");
    }

    @Test
    @DisplayName("calculateTax degrades when the location's country is not a 2-letter ISO code")
    void calculateTax_nonIsoCountry_degradesWithoutCallingPosTax() {
        FacadeContractManifest.Entry calculate = contract("calculateTax");
        gatewayMockServer
                .expect(requestTo(
                        GATEWAY_BASE_URL + calculate.leg("location").expand(Map.of("locationId", LOCATION_ID))))
                .andRespond(withSuccess(
                        "{\"id\":\"" + LOCATION_ID + "\",\"postalCode\":\"62704\",\"country\":\"United States\"}",
                        MediaType.APPLICATION_JSON));

        JsonNode envelope = parse(tool.calculateTax("50", LOCATION_ID));

        directMockServer.verify();
        gatewayMockServer.verify();
        assertThat(envelope.get("status").asText()).isEqualTo("degraded");
        assertThat(envelope.get("sections").get("tax").get("reason").asText())
                .contains("not a 2-letter ISO country code");
    }

    @Test
    @DisplayName("calculateTax renders a forbidden location lookup as not_authorized without body leak")
    void calculateTax_forbiddenLocation_rendersNotAuthorized() {
        FacadeContractManifest.Entry calculate = contract("calculateTax");
        gatewayMockServer
                .expect(requestTo(
                        GATEWAY_BASE_URL + calculate.leg("location").expand(Map.of("locationId", LOCATION_ID))))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"secret\":\"FORBIDDEN-PAYLOAD\"}"));

        String rendered = tool.calculateTax("50", LOCATION_ID);

        directMockServer.verify();
        gatewayMockServer.verify();
        assertThat(rendered).doesNotContain("FORBIDDEN-PAYLOAD");
        JsonNode envelope = parse(rendered);
        assertThat(envelope.get("status").asText()).isEqualTo("degraded");
        assertThat(envelope.get("sections").get("location").get("status").asText())
                .isEqualTo("not_authorized");
        assertThat(envelope.get("sources")).isEmpty();
    }

    @Test
    @DisplayName("calculateTax rejects a non-positive or non-numeric amount without issuing any request")
    void calculateTax_rejectsInvalidAmounts() {
        assertThatThrownBy(() -> tool.calculateTax("0", LOCATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> tool.calculateTax("-10", LOCATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> tool.calculateTax("ten dollars", LOCATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a number");

        directMockServer.verify();
        gatewayMockServer.verify();
    }

    @Test
    @DisplayName("getTaxSummary maps the period onto the accounting tax-liability report via the gateway")
    void getTaxSummary_sendsGetToTaxLiabilityReport() {
        FacadeContractManifest.Entry entry = contract("getTaxSummary");
        gatewayMockServer
                .expect(requestTo(
                        GATEWAY_BASE_URL + entry.expand(Map.of("startDate", "2026-03-01", "endDate", "2026-03-31"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"netTax\":4000}", MediaType.APPLICATION_JSON));

        String result = tool.getTaxSummary("2026-03", null, null);

        directMockServer.verify();
        gatewayMockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getTaxSummary rejects an unsupported period form without issuing a request")
    void getTaxSummary_rejectsUnsupportedPeriod() {
        assertThatThrownBy(() -> tool.getTaxSummary("2025-Q1", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM")
                .hasMessageContaining("YYYY");

        directMockServer.verify();
        gatewayMockServer.verify();
    }

    @Test
    @DisplayName("getTaxSummary takes a startDate/endDate window spanning more than one calendar month "
            + "in a single call")
    void getTaxSummary_takesExplicitDateRangeSpanningMultipleMonths() {
        FacadeContractManifest.Entry entry = contract("getTaxSummary");
        gatewayMockServer
                .expect(requestTo(
                        GATEWAY_BASE_URL + entry.expand(Map.of("startDate", "2026-03-01", "endDate", "2026-08-31"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"netTax\":4000}", MediaType.APPLICATION_JSON));

        String result = tool.getTaxSummary(null, "2026-03-01", "2026-08-31");

        directMockServer.verify();
        gatewayMockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getTaxSummary rejects period together with startDate/endDate without issuing a request")
    void getTaxSummary_rejectsPeriodTogetherWithDateRange() {
        assertThatThrownBy(() -> tool.getTaxSummary("2026-03", "2026-03-01", "2026-08-31"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period")
                .hasMessageContaining("startDate");

        directMockServer.verify();
        gatewayMockServer.verify();
    }

    @Test
    @DisplayName("getTaxSummary rejects an unpaired startDate without issuing a request")
    void getTaxSummary_rejectsUnpairedStartDate() {
        assertThatThrownBy(() -> tool.getTaxSummary(null, "2026-03-01", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startDate")
                .hasMessageContaining("endDate");

        directMockServer.verify();
        gatewayMockServer.verify();
    }

    @Test
    @DisplayName("getTaxRate looks up the location and GETs pos-tax /rates with regionCode and city appended")
    void getTaxRate_appendsRegionCodeAndCityWhenPresent() {
        FacadeContractManifest.Entry rates = contract("getTaxRate").leg("rates");
        gatewayMockServer
                .expect(requestTo(GATEWAY_BASE_URL
                        + contract("getTaxRate").leg("location").expand(Map.of("locationId", LOCATION_ID))))
                .andExpect(method(contract("getTaxRate").leg("location").httpMethod()))
                .andRespond(withSuccess(LOCATION_BODY, MediaType.APPLICATION_JSON));
        directMockServer
                .expect(requestTo(DIRECT_BASE_URL
                        + rates.expand(Map.of("countryCode", "US", "postalCode", "62704"))
                        + "&regionCode=IL&city=Springfield"))
                .andExpect(method(rates.httpMethod()))
                .andRespond(
                        withSuccess("{\"combinedRate\":0.0850,\"source\":\"TEST_MODE\"}", MediaType.APPLICATION_JSON));

        JsonNode envelope = parse(tool.getTaxRate(LOCATION_ID));

        directMockServer.verify();
        gatewayMockServer.verify();
        assertThat(envelope.get("composition").asText()).isEqualTo("taxRateLookup");
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
        assertThat(envelope.get("sections")
                        .get("rates")
                        .get("data")
                        .get("source")
                        .asText())
                .isEqualTo("TEST_MODE");
        assertThat(envelope.get("sources")).extracting(JsonNode::asText).containsExactly("location", "rates");
    }

    @Test
    @DisplayName("getTaxRate omits regionCode and city when the location does not supply them")
    void getTaxRate_omitsRegionCodeAndCityWhenAbsent() {
        String locationBody = "{\"id\":\"" + LOCATION_ID + "\",\"postalCode\":\"94103\",\"country\":\"US\"}";
        FacadeContractManifest.Entry rates = contract("getTaxRate").leg("rates");
        gatewayMockServer
                .expect(requestTo(GATEWAY_BASE_URL
                        + contract("getTaxRate").leg("location").expand(Map.of("locationId", LOCATION_ID))))
                .andRespond(withSuccess(locationBody, MediaType.APPLICATION_JSON));
        directMockServer
                .expect(requestTo(DIRECT_BASE_URL + rates.expand(Map.of("countryCode", "US", "postalCode", "94103"))))
                .andExpect(method(rates.httpMethod()))
                .andRespond(withSuccess("{\"combinedRate\":0.0725}", MediaType.APPLICATION_JSON));

        JsonNode envelope = parse(tool.getTaxRate(LOCATION_ID));

        directMockServer.verify();
        gatewayMockServer.verify();
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
    }

    @Test
    @DisplayName("getTaxRate degrades without calling pos-tax when the location has no usable address")
    void getTaxRate_locationWithoutAddress_degradesWithoutCallingPosTax() {
        gatewayMockServer
                .expect(requestTo(GATEWAY_BASE_URL
                        + contract("getTaxRate").leg("location").expand(Map.of("locationId", LOCATION_ID))))
                .andRespond(withSuccess(
                        "{\"id\":\"" + LOCATION_ID + "\",\"name\":\"Warehouse\"}", MediaType.APPLICATION_JSON));

        JsonNode envelope = parse(tool.getTaxRate(LOCATION_ID));

        directMockServer.verify();
        gatewayMockServer.verify();
        assertThat(envelope.get("status").asText()).isEqualTo("degraded");
        assertThat(envelope.get("sections").get("location").get("status").asText())
                .isEqualTo("ok");
        JsonNode rates = envelope.get("sections").get("rates");
        assertThat(rates.get("status").asText()).isEqualTo("error");
        assertThat(rates.get("reason").asText()).contains("no usable address");
        assertThat(envelope.get("sources")).extracting(JsonNode::asText).containsExactly("location");
    }

    @Test
    @DisplayName("getTaxRate renders a forbidden location lookup as not_authorized without body leak")
    void getTaxRate_forbiddenLocation_rendersNotAuthorized() {
        gatewayMockServer
                .expect(requestTo(GATEWAY_BASE_URL
                        + contract("getTaxRate").leg("location").expand(Map.of("locationId", LOCATION_ID))))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"secret\":\"FORBIDDEN-PAYLOAD\"}"));

        String rendered = tool.getTaxRate(LOCATION_ID);

        directMockServer.verify();
        gatewayMockServer.verify();
        assertThat(rendered).doesNotContain("FORBIDDEN-PAYLOAD");
        JsonNode envelope = parse(rendered);
        assertThat(envelope.get("status").asText()).isEqualTo("degraded");
        assertThat(envelope.get("sections").get("location").get("status").asText())
                .isEqualTo("not_authorized");
        assertThat(envelope.get("sources")).isEmpty();
    }
}
