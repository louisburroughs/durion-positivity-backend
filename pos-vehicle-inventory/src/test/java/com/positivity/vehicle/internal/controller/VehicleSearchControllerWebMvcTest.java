package com.positivity.vehicle.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.positivity.vehicle.internal.security.VehicleInventoryPermissions;
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
 *
 * <p>
 * The POST route is exercised through a real request body rather than a builder,
 * because that is the only thing that proves the DTO is deserializable at all —
 * the defect in #1270 survived precisely because every existing caller of this
 * DTO constructed it in Java.
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
    @DisplayName("POST passes the body's values through to the service unchanged")
    void postPassesBodyThrough() throws Exception {
        when(searchService.search(any())).thenReturn(empty("UNIT-001"));

        // Until #1270 this failed during message conversion for every body: the DTO was
        // @Getter/@Builder over final fields with no @Jacksonized, so Lombok's only constructor
        // was package-private and Jackson had no creator to call. Unlike GET, this route
        // supplies no defaults — whatever the caller sends is what the service must receive.
        mockMvc.perform(post(PATH)
                        .header(AUTH, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"UNIT-001\",\"limit\":5,\"enableContainsMatching\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("UNIT-001"));

        ArgumentCaptor<SearchVehiclesRequest> captor = ArgumentCaptor.forClass(SearchVehiclesRequest.class);
        verify(searchService).search(captor.capture());
        assertThat(captor.getValue().getQuery()).isEqualTo("UNIT-001");
        assertThat(captor.getValue().getLimit()).isEqualTo(5);
        assertThat(captor.getValue().getEnableContainsMatching()).isTrue();
    }

    @Test
    @DisplayName("POST with limit omitted falls back to the DTO's own default")
    void postWithoutLimitUsesDtoDefault() throws Exception {
        when(searchService.search(any())).thenReturn(empty("UNIT-001"));

        // The controller supplies no default on this route, so the cap comes from the DTO
        // getter instead. Without it an uncapped search would reach the service.
        mockMvc.perform(post(PATH)
                        .header(AUTH, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"UNIT-001\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<SearchVehiclesRequest> captor = ArgumentCaptor.forClass(SearchVehiclesRequest.class);
        verify(searchService).search(captor.capture());
        assertThat(captor.getValue().getLimit()).isEqualTo(25);
        // The raw field stays null — the strict-matching default lives in the accessor, not in
        // the deserialized value, so reading the field directly would skip the guard rail.
        assertThat(captor.getValue().getEnableContainsMatching()).isNull();
        assertThat(captor.getValue().isEnableContainsMatching()).isFalse();
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

    @Test
    @DisplayName("registry:view does not open search (Task-5, decisions k/l)")
    void registryViewAuthorityDoesNotOpenSearch() throws Exception {
        // Distinct permission from vehicle-inventory:registry:view, which is a plausible authority
        // for the same operator to hold: a copy-paste onto the wrong constant would let it through.
        mockMvc.perform(get(PATH)
                        .param("q", "HONDA")
                        .header(AUTH, BEARER)
                        .header("X-Authorities", VehicleInventoryPermissions.REGISTRY_VIEW))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(PATH)
                        .header(AUTH, BEARER)
                        .header("X-Authorities", VehicleInventoryPermissions.REGISTRY_VIEW)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"HONDA\"}"))
                .andExpect(status().isForbidden());

        verify(searchService, never()).search(any());
    }
}
