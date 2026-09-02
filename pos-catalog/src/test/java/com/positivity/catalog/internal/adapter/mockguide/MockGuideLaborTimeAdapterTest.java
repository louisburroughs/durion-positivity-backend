package com.positivity.catalog.internal.adapter.mockguide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.catalog.internal.spi.ProviderCallException;
import com.positivity.catalog.internal.spi.model.LaborTimeProviderDescriptor;
import com.positivity.catalog.internal.spi.model.LaborTimeProviderDescriptor.LicenseMode;
import com.positivity.catalog.internal.spi.model.ProviderFeedChunk;
import com.positivity.catalog.internal.spi.model.ProviderFeedRevision;
import com.positivity.catalog.internal.spi.model.ProviderLaborTime;
import com.positivity.catalog.internal.spi.model.VehicleKey;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Wire-contract pinning for the mockguide adapter (#1569 Phase 1): the JSON field names here
 * are the normative provider contract v1 that {@code pos-reference-mock} serves — a rename on
 * either side must fail one of these tests before it fails an import at runtime.
 */
@DisplayName("MockGuideLaborTimeAdapter")
class MockGuideLaborTimeAdapterTest {

    private static final UUID MANIFEST_ID = UUID.fromString("7f1e6b2a-4c5d-4e8f-9a0b-1c2d3e4f5a6b");

    private MockRestServiceServer server;
    private MockGuideLaborTimeAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://mock");
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new MockGuideLaborTimeAdapter(
                new LaborTimeProviderDescriptor("MOCKGUIDE", "Mock guide", LicenseMode.STORE, 100), builder.build());
    }

    @Test
    @DisplayName("getLaborTime maps the contract fields and vehicle query parameters")
    void laborTimeRoundTrip() {
        server.expect(requestTo("http://mock/mock/labor-guide/v1/labor-times?year=2019-2023&make=Honda"
                        + "&model=Civic&providerOperationCode=MG-BRAKE-PAD-FRONT"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"providerOperationCode":"MG-BRAKE-PAD-FRONT","hours":1.5,
                         "timeType":"RETAIL_FLAT_RATE","includedOperations":["BRAKE-PAD-FRONT"],
                         "overlapGroup":"WHEEL-OFF","sourceRevision":"2026-09-01",
                         "publishedAt":"2026-09-01","notes":"n"}
                        """, MediaType.APPLICATION_JSON));

        Optional<ProviderLaborTime> time =
                adapter.getLaborTime(new VehicleKey("2019-2023", "Honda", "Civic", null, null), "MG-BRAKE-PAD-FRONT");

        assertThat(time).isPresent();
        assertThat(time.get().hours()).isEqualByComparingTo("1.5");
        assertThat(time.get().overlapGroup()).isEqualTo("WHEEL-OFF");
        assertThat(time.get().includedOperations()).containsExactly("BRAKE-PAD-FRONT");
        server.verify();
    }

    @Test
    @DisplayName("a vendor 404 is a miss — the vendor answered 'no time', not 'I am down'")
    void notFoundIsMiss() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://mock/mock/labor-guide/v1/labor-times")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(adapter.getLaborTime(VehicleKey.any(), "MG-NOPE")).isEmpty();
    }

    @Test
    @DisplayName("a vendor 5xx is a ProviderCallException — typed degradation upstream")
    void serverErrorIsProviderCallException() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://mock/mock/labor-guide/v1/labor-times")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> adapter.getLaborTime(VehicleKey.any(), "MG-X"))
                .isInstanceOf(ProviderCallException.class);
    }

    @Test
    @DisplayName("manifest and chunk fetches map the feed contract")
    void feedRoundTrip() {
        server.expect(requestTo("http://mock/mock/labor-guide/v1/feed/manifest"))
                .andRespond(withSuccess("""
                        {"importManifestId":"%s","sourceRevision":"2026-09-01",
                         "expectedChunkCount":6,"expectedLineCount":294,"contentChecksum":"abc"}
                        """.formatted(MANIFEST_ID), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://mock/mock/labor-guide/v1/feed/chunks/1?manifestId=" + MANIFEST_ID))
                .andRespond(withSuccess("""
                        {"importManifestId":"%s","chunkSequence":1,
                         "lines":[{"providerOperationCode":"MG-DIAG-SCAN","vehicleYear":null,"make":null,
                                   "model":null,"submodel":null,"engineCode":null,"hours":1.0,
                                   "timeType":"RETAIL_FLAT_RATE","overlapGroup":null,
                                   "includedOperations":[],"publishedAt":"2026-09-01"}]}
                        """.formatted(MANIFEST_ID), MediaType.APPLICATION_JSON));

        ProviderFeedRevision manifest = adapter.openFeedRevision(null);
        assertThat(manifest.expectedChunkCount()).isEqualTo(6);
        assertThat(manifest.expectedLineCount()).isEqualTo(294);

        ProviderFeedChunk chunk = adapter.fetchFeedChunk(MANIFEST_ID, 1);
        assertThat(chunk.lines()).hasSize(1);
        assertThat(chunk.lines().getFirst().providerOperationCode()).isEqualTo("MG-DIAG-SCAN");
        assertThat(chunk.lines().getFirst().make()).isNull();
        server.verify();
    }
}
