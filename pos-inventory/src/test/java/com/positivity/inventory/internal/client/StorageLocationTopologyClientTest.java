package com.positivity.inventory.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.inventory.internal.client.StorageLocationTopologyClient.StorageLocationNode;
import com.positivity.inventory.internal.exception.LocationNotFoundException;
import com.positivity.inventory.internal.exception.LocationServiceUnavailableException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link StorageLocationTopologyClient} — CAP-218 #658.
 * Consumes the pos-location topology contract from CAP-214 #655.
 */
class StorageLocationTopologyClientTest {

    private static final UUID SITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private record Fixture(MockRestServiceServer server, StorageLocationTopologyClient client) {}

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(server, new StorageLocationTopologyClient(builder, "location"));
    }

    @Test
    void fetchSiteTopology_mapsContractFields() {
        // Issue #658: consumer-side pin of id/name/type/status/parentStorageLocationId
        Fixture f = fixture();
        String body = """
                [{"id":"00000000-0000-0000-0000-0000000000f1","name":"Floor A","type":"FLOOR",
                  "status":"ACTIVE","parentStorageLocationId":null},
                 {"id":"00000000-0000-0000-0000-0000000000e1","name":"Shelf A-1","type":"SHELF",
                  "status":"INACTIVE","parentStorageLocationId":"00000000-0000-0000-0000-0000000000f1"}]
                """;
        f.server()
                .expect(requestTo("http://location/v1/locations/" + SITE_ID + "/storage-locations/topology"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-inventory"))
                .andExpect(header("X-Authorities", "location:read"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<StorageLocationNode> nodes = f.client().fetchSiteTopology(SITE_ID);

        assertThat(nodes).hasSize(2);
        assertThat(nodes.getFirst().name()).isEqualTo("Floor A");
        assertThat(nodes.getFirst().parentStorageLocationId()).isNull();
        assertThat(nodes.getLast().status()).isEqualTo("INACTIVE");
        assertThat(nodes.getLast().parentStorageLocationId())
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-0000000000f1"));
        f.server().verify();
    }

    @Test
    void fetchSiteTopology_404_throwsLocationNotFound() {
        // Issue #658: unknown site surfaces as LocationNotFoundException → 404
        Fixture f = fixture();
        f.server()
                .expect(requestTo("http://location/v1/locations/" + SITE_ID + "/storage-locations/topology"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> f.client().fetchSiteTopology(SITE_ID)).isInstanceOf(LocationNotFoundException.class);
    }

    @Test
    void fetchSiteTopology_5xx_throwsServiceUnavailable() {
        // Issue #658: never fabricate partial topology on upstream failure
        Fixture f = fixture();
        f.server()
                .expect(requestTo("http://location/v1/locations/" + SITE_ID + "/storage-locations/topology"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> f.client().fetchSiteTopology(SITE_ID))
                .isInstanceOf(LocationServiceUnavailableException.class);
    }
}
