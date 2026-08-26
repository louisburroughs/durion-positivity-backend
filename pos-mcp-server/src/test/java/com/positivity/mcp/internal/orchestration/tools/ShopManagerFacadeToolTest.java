package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link ShopManagerFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 * The clock is fixed so the schedule leg's required {@code date} param is deterministic.
 */
class ShopManagerFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";
    private static final String SHOP_ID = "01960003-0000-7000-8000-0000000000c0";
    private static final String TODAY = "2026-08-26";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse(TODAY + "T10:15:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockRestServiceServer mockServer;
    private ShopManagerFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("ShopManagerFacadeTool." + toolMethod);
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
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        FacadeContractManifest.Entry status = contract("getShopStatus");
        tool = new ShopManagerFacadeTool(
                builder,
                FIXED_CLOCK,
                BASE_URL,
                status.leg("location").template(),
                status.leg("schedule").template(),
                status.leg("openWorkorders").template(),
                contract("searchShops").template());
    }

    @Test
    @DisplayName("getShopStatus composes location, today's schedule, and open workorders for the location id")
    void getShopStatus_composesLocationScheduleAndWip() {
        FacadeContractManifest.Entry status = contract("getShopStatus");
        mockServer
                .expect(requestTo(BASE_URL + status.leg("location").expand(Map.of("locationId", SHOP_ID))))
                .andExpect(method(status.leg("location").httpMethod()))
                .andRespond(withSuccess(
                        "{\"id\":\"" + SHOP_ID + "\",\"name\":\"Downtown Garage\"}", MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(
                        BASE_URL + status.leg("schedule").expand(Map.of("locationId", SHOP_ID, "date", TODAY))))
                .andExpect(method(status.leg("schedule").httpMethod()))
                .andRespond(withSuccess("{\"lanes\":[]}", MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(BASE_URL + status.leg("openWorkorders").expand(Map.of("locationId", SHOP_ID))))
                .andExpect(method(status.leg("openWorkorders").httpMethod()))
                .andRespond(withSuccess("{\"content\":[{\"workorderId\":\"WO-1\"}]}", MediaType.APPLICATION_JSON));

        JsonNode envelope = parse(tool.getShopStatus(SHOP_ID));

        mockServer.verify();
        assertThat(envelope.get("composition").asText()).isEqualTo("shopStatus");
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
        assertThat(envelope.get("sections").get("location").get("data").get("name").asText())
                .isEqualTo("Downtown Garage");
        assertThat(envelope.get("sections").get("schedule").get("status").asText())
                .isEqualTo("ok");
        assertThat(envelope.get("sections")
                        .get("openWorkorders")
                        .get("data")
                        .get("content")
                        .get(0)
                        .get("workorderId")
                        .asText())
                .isEqualTo("WO-1");
        assertThat(envelope.get("sources"))
                .extracting(JsonNode::asText)
                .containsExactly("location", "schedule", "openWorkorders");
    }

    @Test
    @DisplayName("getShopStatus degrades when the required location leg fails, and a 403 schedule leg leaks nothing")
    void getShopStatus_failedLocationAndForbiddenSchedule_degrades() {
        FacadeContractManifest.Entry status = contract("getShopStatus");
        mockServer
                .expect(requestTo(BASE_URL + status.leg("location").expand(Map.of("locationId", SHOP_ID))))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        mockServer
                .expect(requestTo(
                        BASE_URL + status.leg("schedule").expand(Map.of("locationId", SHOP_ID, "date", TODAY))))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"secret\":\"FORBIDDEN-PAYLOAD\"}"));
        mockServer
                .expect(requestTo(BASE_URL + status.leg("openWorkorders").expand(Map.of("locationId", SHOP_ID))))
                .andRespond(withSuccess("{\"content\":[]}", MediaType.APPLICATION_JSON));

        String rendered = tool.getShopStatus(SHOP_ID);

        mockServer.verify();
        assertThat(rendered).doesNotContain("FORBIDDEN-PAYLOAD");
        JsonNode envelope = parse(rendered);
        assertThat(envelope.get("status").asText()).isEqualTo("degraded");
        assertThat(envelope.get("sections").get("location").get("status").asText())
                .isEqualTo("error");
        assertThat(envelope.get("sections").get("schedule").get("status").asText())
                .isEqualTo("not_authorized");
        assertThat(envelope.get("sources")).extracting(JsonNode::asText).containsExactly("openWorkorders");
    }

    @Test
    @DisplayName("getShopQueue composes open workorders (required) and today's schedule")
    void getShopQueue_composesWipAndSchedule() {
        FacadeContractManifest.Entry queue = contract("getShopQueue");
        mockServer
                .expect(requestTo(BASE_URL + queue.leg("openWorkorders").expand(Map.of("locationId", SHOP_ID))))
                .andExpect(method(queue.leg("openWorkorders").httpMethod()))
                .andRespond(withSuccess("{\"content\":[{\"workorderId\":\"WO-9\"}]}", MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(
                        BASE_URL + queue.leg("schedule").expand(Map.of("locationId", SHOP_ID, "date", TODAY))))
                .andExpect(method(queue.leg("schedule").httpMethod()))
                .andRespond(withSuccess("{\"lanes\":[]}", MediaType.APPLICATION_JSON));

        JsonNode envelope = parse(tool.getShopQueue(SHOP_ID));

        mockServer.verify();
        assertThat(envelope.get("composition").asText()).isEqualTo("shopQueue");
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
        assertThat(envelope.get("sections")
                        .get("openWorkorders")
                        .get("data")
                        .get("content")
                        .get(0)
                        .get("workorderId")
                        .asText())
                .isEqualTo("WO-9");
        assertThat(envelope.get("sources"))
                .extracting(JsonNode::asText)
                .containsExactly("openWorkorders", "schedule");
    }

    @Test
    @DisplayName("getShopQueue renders a 403 workorder leg as not_authorized and degrades")
    void getShopQueue_forbiddenWip_degradesWithoutBodyLeak() {
        FacadeContractManifest.Entry queue = contract("getShopQueue");
        mockServer
                .expect(requestTo(BASE_URL + queue.leg("openWorkorders").expand(Map.of("locationId", SHOP_ID))))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"secret\":\"FORBIDDEN-PAYLOAD\"}"));
        mockServer
                .expect(requestTo(
                        BASE_URL + queue.leg("schedule").expand(Map.of("locationId", SHOP_ID, "date", TODAY))))
                .andRespond(withSuccess("{\"lanes\":[]}", MediaType.APPLICATION_JSON));

        String rendered = tool.getShopQueue(SHOP_ID);

        mockServer.verify();
        assertThat(rendered).doesNotContain("FORBIDDEN-PAYLOAD");
        JsonNode envelope = parse(rendered);
        assertThat(envelope.get("status").asText()).isEqualTo("degraded");
        assertThat(envelope.get("sections").get("openWorkorders").get("status").asText())
                .isEqualTo("not_authorized");
        assertThat(envelope.get("sources")).extracting(JsonNode::asText).containsExactly("schedule");
    }

    @Test
    @DisplayName("searchShops fetches the location roster and contains-filters by name/code")
    void searchShops_filtersLocationRoster() {
        FacadeContractManifest.Entry entry = contract("searchShops");
        String roster = """
                [
                  {"id":"loc-1","name":"Downtown Garage","code":"DTG"},
                  {"id":"loc-2","name":"Airport Service Center","code":"ASC"}
                ]
                """;
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of())))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess(roster, MediaType.APPLICATION_JSON));

        String result = tool.searchShops("downtown");

        mockServer.verify();
        assertThat(result).contains("Downtown Garage");
        assertThat(result).doesNotContain("Airport Service Center");
    }
}
