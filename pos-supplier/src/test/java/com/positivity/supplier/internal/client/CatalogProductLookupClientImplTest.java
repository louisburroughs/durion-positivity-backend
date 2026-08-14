package com.positivity.supplier.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.supplier.internal.client.CatalogProductLookupClient.LookupResult;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("pos-catalog product-code lookup client (#1224/#1232)")
class CatalogProductLookupClientImplTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");

    private record Fixture(CatalogProductLookupClient client, MockRestServiceServer server) {}

    private static Fixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new CatalogProductLookupClientImpl(builder, "catalog", "/v1/products"), server);
    }

    @Test
    void resolvesAMatchAndSendsTheServiceIdentityHeaders() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(Matchers.containsString("http://catalog/v1/products/by-code")))
                .andExpect(queryParam("codeType", "EAN"))
                .andExpect(queryParam("code", "3528709999083"))
                .andExpect(header("X-Authorities", "ROLE_CATALOG_VIEW"))
                .andExpect(header("X-User", "pos-supplier-service"))
                .andRespond(withSuccess("{\"productId\":\"" + PRODUCT_ID + "\"}", MediaType.APPLICATION_JSON));

        LookupResult result = fixture.client().findProductByCode("EAN", "3528709999083");

        assertThat(result).isEqualTo(new LookupResult.Matched(PRODUCT_ID));
        fixture.server().verify();
    }

    @Test
    void treatsA404AsAGenuineMiss() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(Matchers.any(String.class))).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(fixture.client().findProductByCode("EAN", "3528709999083"))
                .isInstanceOf(LookupResult.NotFound.class);
    }

    @Test
    void treatsA409AsAmbiguousCatalogData() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(Matchers.any(String.class))).andRespond(withStatus(HttpStatus.CONFLICT));

        assertThat(fixture.client().findProductByCode("EAN", "3528709999083"))
                .isInstanceOf(LookupResult.Ambiguous.class);
    }

    @Test
    void treatsAServerErrorAsUnavailableRatherThanAsAMiss() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(Matchers.any(String.class)))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(fixture.client().findProductByCode("EAN", "3528709999083"))
                .isInstanceOf(LookupResult.Unavailable.class);
    }

    @Test
    void treatsAnAuthFailureAsUnavailableSoLinesStayRecoverable() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(Matchers.any(String.class))).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(fixture.client().findProductByCode("EAN", "3528709999083"))
                .isInstanceOf(LookupResult.Unavailable.class);
    }

    @Test
    void treatsAnEmptyBodyAsAMiss() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(Matchers.any(String.class)))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(fixture.client().findProductByCode("UPC", "0123456789012"))
                .isInstanceOf(LookupResult.NotFound.class);
    }
}
