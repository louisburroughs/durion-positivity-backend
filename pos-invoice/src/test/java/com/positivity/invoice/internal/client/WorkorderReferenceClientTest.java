package com.positivity.invoice.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link WorkorderReferenceClient}. Binds {@link MockRestServiceServer} to the
 * {@link RestClient.Builder} so id parsing, the batch {@code numbers:resolve} mapping, and
 * silent-failure behaviour are exercised without a live workorder service.
 */
class WorkorderReferenceClientTest {

    private static final String BASE_URL = "http://pos-workorder:8080/v1/workorders";
    private static final UUID WO_1 = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID WO_2 = UUID.fromString("22222222-0000-0000-0000-000000000002");

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private WorkorderReferenceClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new WorkorderReferenceClient(builder, BASE_URL);
    }

    @Test
    void searchIdsByNumber_blankQuery_returnsEmptyWithoutCall() {
        assertThat(client.searchIdsByNumber(" ", 50)).isEmpty();
        server.verify();
    }

    @Test
    void searchIdsByNumber_parsesContentIds_skippingBadUuids() {
        server.expect(requestTo(Matchers.containsString("/search?q=WO-2026&size=50")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"content\":[{\"workorderId\":\"" + WO_1 + "\"},{\"workorderId\":\"not-a-uuid\"},"
                                + "{\"noId\":1},{\"workorderId\":\"" + WO_2 + "\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.searchIdsByNumber("WO-2026", 50)).containsExactly(WO_1, WO_2);
        server.verify();
    }

    @Test
    void resolveNumbers_emptyInput_returnsEmptyWithoutCall() {
        assertThat(client.resolveNumbers(List.of())).isEmpty();
        server.verify();
    }

    @Test
    void resolveNumbers_mapsBatchResponse_skippingBadIdsAndBlankNumbers() {
        server.expect(requestTo(BASE_URL + "/numbers:resolve"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "[{\"workorderId\":\"" + WO_1 + "\",\"workorderNumber\":\" WO-2026-1001 \"},"
                                + "{\"workorderId\":\"" + WO_2 + "\",\"workorderNumber\":\"\"},"
                                + "{\"workorderId\":\"bad\",\"workorderNumber\":\"WO-X\"}]",
                        MediaType.APPLICATION_JSON));

        assertThat(client.resolveNumbers(List.of(WO_1, WO_2)))
                .containsExactly(java.util.Map.entry(WO_1, "WO-2026-1001"));
        server.verify();
    }

    @Test
    void resolveNumbers_remoteError_isSwallowed_returningEmptyMap() {
        server.expect(requestTo(BASE_URL + "/numbers:resolve")).andRespond(withServerError());

        assertThat(client.resolveNumbers(List.of(WO_1))).isEmpty();
        server.verify();
    }
}
