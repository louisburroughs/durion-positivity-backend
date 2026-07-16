package com.positivity.warranty.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link WorkorderClientImpl} wire behavior against a {@link MockRestServiceServer}:
 * read-only client, all failure modes degrade gracefully (empty list / empty Optional).
 */
class WorkorderClientTest {

    private static final UUID WORKORDER_ID = UUID.fromString("66666666-6666-7666-8666-666666666666");
    private static final UUID CUSTOMER_ID = UUID.fromString("77777777-7777-7777-8777-777777777777");
    private static final UUID VEHICLE_ID = UUID.fromString("88888888-8888-7888-8888-888888888888");

    private MockRestServiceServer server;
    private WorkorderClientImpl client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new WorkorderClientImpl(builder, "workorder", "/v1/workorders");
    }

    @Test
    void searchWorkordersMapsPageContentAndPassesFilters() {
        server.expect(requestToUriTemplate(
                        "http://workorder/v1/workorders/search?size=50&sort=createdAt,desc&customerId={c}&vehicleId={v}",
                        CUSTOMER_ID,
                        VEHICLE_ID))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-warranty-service"))
                .andExpect(header("X-Authorities", "workorder:workorder:view"))
                .andRespond(withSuccess(
                        "{\"content\":[{\"workorderId\":\"" + WORKORDER_ID + "\","
                                + "\"workorderNumber\":\"WO-100\",\"status\":\"COMPLETED\","
                                + "\"customerId\":\"" + CUSTOMER_ID + "\",\"vehicleId\":\"" + VEHICLE_ID + "\","
                                + "\"vin\":\"1HGCM82633A004352\"}]}",
                        MediaType.APPLICATION_JSON));

        var summaries = client.searchWorkorders(CUSTOMER_ID, VEHICLE_ID);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst().workorderId()).isEqualTo(WORKORDER_ID);
        assertThat(summaries.getFirst().workorderNumber()).isEqualTo("WO-100");
        assertThat(summaries.getFirst().vin()).isEqualTo("1HGCM82633A004352");
        server.verify();
    }

    @Test
    void searchWorkordersOmitsNullFilters() {
        server.expect(requestToUriTemplate(
                        "http://workorder/v1/workorders/search?size=50&sort=createdAt,desc&customerId={c}",
                        CUSTOMER_ID))
                .andExpect(queryParam("customerId", CUSTOMER_ID.toString()))
                .andRespond(withSuccess("{\"content\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.searchWorkorders(CUSTOMER_ID, null)).isEmpty();
        server.verify();
    }

    @Test
    void searchWorkordersDegradesToEmptyListOnHttpError() {
        server.expect(requestToUriTemplate(
                        "http://workorder/v1/workorders/search?size=50&sort=createdAt,desc&customerId={c}",
                        CUSTOMER_ID))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(client.searchWorkorders(CUSTOMER_ID, null)).isEmpty();
        server.verify();
    }

    @Test
    void getWorkorderDetailMapsServiceAndPartLines() {
        server.expect(requestToUriTemplate("http://workorder/v1/workorders/{id}/detail", WORKORDER_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"workorderId\":\"" + WORKORDER_ID + "\",\"workorderNumber\":\"WO-100\","
                                + "\"customerId\":\"" + CUSTOMER_ID + "\",\"vehicleId\":\"" + VEHICLE_ID + "\","
                                + "\"services\":[{\"description\":\"Replace alternator\",\"quantity\":2.5,"
                                + "\"unitPrice\":120.00,\"lineTotal\":300.00}],"
                                + "\"parts\":[{\"description\":\"Alternator\",\"quantity\":1,"
                                + "\"unitPrice\":199.99,\"lineTotal\":199.99,"
                                + "\"photoEvidenceUrl\":\"https://example.test/photo.jpg\"}]}",
                        MediaType.APPLICATION_JSON));

        Optional<WorkorderClient.WorkorderDetail> detail = client.getWorkorderDetail(WORKORDER_ID);

        assertThat(detail).isPresent();
        WorkorderClient.WorkorderDetail workorder = detail.orElseThrow();
        assertThat(workorder.workorderNumber()).isEqualTo("WO-100");
        assertThat(workorder.services()).hasSize(1);
        assertThat(workorder.services().getFirst().laborHours()).isEqualByComparingTo("2.5");
        assertThat(workorder.services().getFirst().laborRate()).isEqualByComparingTo("120.00");
        assertThat(workorder.parts()).hasSize(1);
        assertThat(workorder.parts().getFirst().photoEvidenceUrl()).isEqualTo("https://example.test/photo.jpg");
        server.verify();
    }

    @Test
    void getWorkorderDetailDegradesToEmptyOn404() {
        server.expect(requestToUriTemplate("http://workorder/v1/workorders/{id}/detail", WORKORDER_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getWorkorderDetail(WORKORDER_ID)).isEmpty();
        server.verify();
    }

    @Test
    void getWorkorderDetailDegradesToEmptyWhenServiceUnreachable() {
        server.expect(requestToUriTemplate("http://workorder/v1/workorders/{id}/detail", WORKORDER_ID))
                .andRespond(withException(new IOException("connection refused")));

        assertThat(client.getWorkorderDetail(WORKORDER_ID)).isEmpty();
        server.verify();
    }
}
