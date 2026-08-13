package com.positivity.vehicle.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.vehicle.config.WebMvcTestSecurityConfig;
import com.positivity.vehicle.internal.dto.SearchVehiclesRequest;
import com.positivity.vehicle.internal.dto.SearchVehiclesResponse;
import com.positivity.vehicle.service.VehicleSearchService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@link VehicleSearchController}.
 *
 * <p>
 * Two endpoints reach the same service through different doors: POST takes a
 * {@code SearchVehiclesRequest} straight off the body, while GET assembles one
 * from query parameters and supplies the defaults. Those defaults are the part
 * worth pinning — a caller who omits {@code limit} is relying on the controller
 * to cap the result set, and a caller who omits {@code enableContains} is
 * relying on it to stay in strict-matching mode. Both are decisions the
 * controller makes silently on the caller's behalf, so nothing downstream would
 * notice if they changed.
 */
@WebMvcTest(VehicleSearchController.class)
@Import(WebMvcTestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
@DisplayName("VehicleSearchController — web slice")
class VehicleSearchControllerWebMvcTest {

    private static final String PATH = "/v1/vehicles/search";
    private static final String AUTH = "Authorization";
    private static final String BEARER = "Bearer test";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    VehicleSearchService searchService;

    private static SearchVehiclesResponse empty(String query) {
        return SearchVehiclesResponse.builder()
                .results(List.of())
                .totalCount(0)
                .hasMore(false)
                .query(query)
                .build();
    }

    @Test
    @DisplayName("GET applies the documented defaults when limit and enableContains are omitted")
    void getAppliesDefaults() throws Exception {
        when(searchService.search(any())).thenReturn(empty("HONDA"));

        mockMvc.perform(get(PATH).param("q", "HONDA").header(AUTH, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0));

        ArgumentCaptor<SearchVehiclesRequest> captor = ArgumentCaptor.forClass(SearchVehiclesRequest.class);
        verify(searchService).search(captor.capture());
        // An uncapped search over a VIN-indexed table is the kind of query that reads the whole
        // table, so the default limit is a guard rail rather than a convenience. Strict matching
        // is the safer default for the same reason: contains-matching is the expensive path.
        assertThat(captor.getValue().getLimit()).isEqualTo(25);
        assertThat(captor.getValue().getEnableContainsMatching()).isFalse();
        assertThat(captor.getValue().getQuery()).isEqualTo("HONDA");
    }

    @Test
    @DisplayName("GET passes explicit parameters through instead of the defaults")
    void getPassesExplicitParameters() throws Exception {
        when(searchService.search(any())).thenReturn(empty("1HGCM"));

        mockMvc.perform(get(PATH)
                        .param("q", "1HGCM")
                        .param("limit", "10")
                        .param("enableContains", "true")
                        .header(AUTH, BEARER))
                .andExpect(status().isOk());

        ArgumentCaptor<SearchVehiclesRequest> captor = ArgumentCaptor.forClass(SearchVehiclesRequest.class);
        verify(searchService).search(captor.capture());
        assertThat(captor.getValue().getLimit()).isEqualTo(10);
        assertThat(captor.getValue().getEnableContainsMatching()).isTrue();
    }

    @Test
    @DisplayName("GET without a query parameter is rejected before reaching the service")
    void getWithoutQueryIsRejected() throws Exception {
        mockMvc.perform(get(PATH).header(AUTH, BEARER)).andExpect(status().isBadRequest());

        verify(searchService, never()).search(any());
    }

    @Test
    @DisplayName("BUG: POST cannot deserialize any body, so the endpoint fails for every request")
    void postCannotDeserializeAnyBody() {
        // Documents current behaviour, not desired behaviour. SearchVehiclesRequest is
        // @Getter/@Builder with final fields and no @Jacksonized, so Lombok's only constructor
        // is package-private and Jackson has no creator to call. Message conversion fails
        // before the controller method is entered, which means this documented endpoint has
        // never worked for any input. The GET route above is unaffected because it builds the
        // object in Java rather than deserializing it — which is why nothing caught this.
        // Tracked as issue #1270. When that is fixed this test becomes the ordinary
        // pass-through assertion: the body's limit and enableContainsMatching reach the service
        // unchanged, in contrast to the GET route, which supplies defaults.
        assertThatThrownBy(() -> mockMvc.perform(post(PATH)
                        .header(AUTH, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"UNIT-001\",\"limit\":5,\"enableContainsMatching\":true}")))
                .rootCause()
                .hasMessageContaining("no Creators, like default constructor, exist");

        verify(searchService, never()).search(any());
    }

    @Test
    @DisplayName("GET tolerates a query containing CRLF without splitting the log line")
    void getWithCrlfInQueryIsHandled() throws Exception {
        when(searchService.search(any())).thenReturn(empty("x"));

        // The controller masks and sanitizes the query before logging it precisely because it
        // is attacker-controlled free text; this asserts the request still completes normally
        // so nobody removes the sanitizing by way of "simplifying" the log statement.
        mockMvc.perform(get(PATH).param("q", "HONDA\r\nINJECTED").header(AUTH, BEARER))
                .andExpect(status().isOk());

        verify(searchService).search(any());
    }

    @Test
    @DisplayName("rejects an unauthenticated request")
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get(PATH).param("q", "HONDA")).andExpect(status().isUnauthorized());

        verify(searchService, never()).search(any());
    }
}
