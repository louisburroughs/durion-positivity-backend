package com.positivity.invoice.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.invoice.internal.config.InvoiceService;
import com.positivity.invoice.internal.dto.InvoiceDetailsResponse;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.security.InvoicePermissions;
import com.positivity.invoice.internal.service.InvoiceFinalizationService;
import com.positivity.invoice.internal.service.OrderInvoiceService;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.security.common.LocationScopeDeniedException;
import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import com.positivity.shared.dto.OrderInvoiceCreationRequest;
import com.positivity.shared.dto.OrderInvoiceResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-boundary proof for #1872 (ADR-0061 §3): {@code POST /v1/invoices} and
 * {@code POST /v1/invoices/from-order} apply the caller's location scope on top of
 * {@code invoice:manage}, gated on the request's {@code locationId}; and a denial raised by
 * {@code InvoiceServiceImpl} on {@code GET /v1/invoices/{invoiceId}} reaches the client as
 * {@code 403 LOCATION_SCOPE_DENIED} rather than this module's plain {@code FORBIDDEN}.
 *
 * <p>The {@link LocationScope} is installed through the authentication details map exactly where
 * {@code GatewayAuthoritiesFilter} puts it, with a map-backed {@link LocationAncestorResolver}
 * standing in for the replica. The gateway chain itself is not imported: with no {@code X-*}
 * headers {@code GatewayAuthoritiesFilter} clears the context, and a scoped
 * {@code LocationScope} cannot be expressed through the legacy {@code X-Authorities} header at all.
 * Instead the caller is installed through {@link TestSecurityContextHolder} and only method
 * security is enabled — the same shape pos-workorder's {@code WipControllerTest} uses. No
 * {@code SecurityFilterChain} is declared on purpose: a {@code @WebMvcTest} slice runs MockMvc
 * without Spring Security's filter chain, and declaring one would put {@code SecurityContextHolderFilter}
 * in front of the controller, where it replaces the test caller with an anonymous context.
 *
 * <p>{@link LocationScopeAutoConfiguration} is imported explicitly: {@code @WebMvcTest} does not
 * load arbitrary {@code @AutoConfiguration} classes, and the point of asserting
 * {@code LOCATION_SCOPE_DENIED} is to prove the highest-precedence advice wins over this module's
 * {@code AccessDeniedException} handler.
 */
@WebMvcTest(InvoiceController.class)
@Import({LocationScopeAutoConfiguration.class, InvoiceControllerLocationScopeTest.SliceTestConfig.class})
@DisplayName("InvoiceController location scope (ADR-0061, #1872)")
class InvoiceControllerLocationScopeTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);

    private static final String INVOICES_URL = "/v1/invoices";
    private static final String FROM_ORDER_URL = "/v1/invoices/from-order";

    /** The node the scoped caller is assigned: a region above {@link #SHOP_A}. */
    private static final UUID REGION_NODE = UUID.fromString("019200aa-0000-7000-8000-00000000a000");

    private static final UUID SHOP_A = UUID.fromString("019200aa-0000-7000-8000-00000000000a");
    private static final UUID SHOP_B = UUID.fromString("019200aa-0000-7000-8000-00000000000b");
    private static final UUID UNKNOWN_LOCATION = UUID.fromString("019200aa-0000-7000-8000-0000000000ff");
    private static final UUID INVOICE_ID = UUID.fromString("019200aa-0000-7000-8000-000000000101");
    private static final UUID WORKORDER_ID = UUID.fromString("019200aa-0000-7000-8000-000000000201");
    private static final UUID ORDER_ID = UUID.fromString("019200aa-0000-7000-8000-000000000301");

    /** Replica stand-in: SHOP_A sits under REGION_NODE on the OTHER dimension; SHOP_B does not. */
    private static final Map<UUID, AncestorSets> REPLICA = Map.of(
            SHOP_A, new AncestorSets(Set.of(SHOP_A), Set.of(SHOP_A, REGION_NODE)),
            SHOP_B, new AncestorSets(Set.of(SHOP_B), Set.of(SHOP_B)));

    private static final LocationAncestorResolver RESOLVER =
            locationId -> REPLICA.getOrDefault(locationId, AncestorSets.EMPTY);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InvoiceService invoiceService;

    @MockitoBean
    private InvoiceFinalizationService invoiceFinalizationService;

    @MockitoBean
    private OrderInvoiceService orderInvoiceService;

    @AfterEach
    void clearCaller() {
        TestSecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------------------------
    // Caller shapes
    // ---------------------------------------------------------------------------------------

    /** A post-rollout token whose {@code invoice:manage} and {@code invoice:invoice:view} are OTHER-scoped to REGION_NODE. */
    private static Authentication scopedBiller() {
        return caller(
                List.of(InvoicePermissions.MANAGE, InvoicePermissions.VIEW),
                LocationScope.of(
                        Set.of(),
                        Set.of(InvoicePermissions.MANAGE, InvoicePermissions.VIEW),
                        Optional.of(Set.of(REGION_NODE)),
                        true,
                        RESOLVER));
    }

    /** A post-rollout token that carries claims but whose invoice grants are global. */
    private static Authentication globalBiller() {
        return caller(
                List.of(InvoicePermissions.MANAGE, InvoicePermissions.VIEW),
                LocationScope.of(Set.of(), Set.of(), Optional.of(Set.of(REGION_NODE)), true, RESOLVER));
    }

    /** A pre-rollout token: no {@code loc_*} claims at all, so no scope detail is attached. */
    private static Authentication preRolloutBiller() {
        return caller(List.of(InvoicePermissions.MANAGE, InvoicePermissions.VIEW), null);
    }

    private static void as(Authentication caller) {
        TestSecurityContextHolder.setAuthentication(caller);
    }

    private static Authentication caller(List<String> authorities, LocationScope scope) {
        var token = new UsernamePasswordAuthenticationToken(
                "invoice-test-user",
                null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList());
        Map<String, Object> details = scope == null
                ? Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "invoice-test-user")
                : Map.of(
                        GatewaySecurityConstants.DETAIL_USERNAME,
                        "invoice-test-user",
                        GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                        scope);
        token.setDetails(details);
        return token;
    }

    // ---------------------------------------------------------------------------------------
    // Request bodies
    // ---------------------------------------------------------------------------------------

    private static String workorderInvoiceBody(UUID locationId) {
        String location = locationId == null ? "" : ",\"locationId\":\"" + locationId + "\"";
        return "{\"workorderId\":\"" + WORKORDER_ID + "\"" + location + ",\"lineItems\":[]}";
    }

    private static String orderInvoiceBody(UUID locationId) {
        String location = locationId == null ? "" : ",\"locationId\":\"" + locationId + "\"";
        return "{\"orderId\":\"" + ORDER_ID + "\"" + location
                + ",\"subtotal\":100.00,\"taxAmount\":8.00,\"totalAmount\":108.00,"
                + "\"lines\":[{\"description\":\"Brake pad set\",\"quantity\":2,\"unitPrice\":50.00,\"amount\":100.00}]}";
    }

    private void stubCreateInvoice() {
        when(invoiceService.createInvoice(any(InvoiceCreationRequest.class)))
                .thenReturn(InvoiceGenerationResponse.builder()
                        .invoiceId(INVOICE_ID)
                        .build());
    }

    private void stubCreateFromOrder() {
        when(orderInvoiceService.createInvoiceForOrder(any(OrderInvoiceCreationRequest.class)))
                .thenReturn(OrderInvoiceResponse.builder()
                        .invoiceId(INVOICE_ID)
                        .existing(false)
                        .build());
    }

    // ---------------------------------------------------------------------------------------
    // POST /v1/invoices
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("createInvoice")
    class CreateInvoice {

        @Test
        @DisplayName("scoped caller raising an invoice at a location in reach answers 201")
        void scopedCallerInReach() throws Exception {
            stubCreateInvoice();

            as(scopedBiller());
            mockMvc.perform(post(INVOICES_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(workorderInvoiceBody(SHOP_A)))
                    .andExpect(status().isCreated());

            verify(invoiceService).createInvoice(any(InvoiceCreationRequest.class));
        }

        @Test
        @DisplayName("scoped caller raising an invoice at a location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void scopedCallerOutOfReach() throws Exception {
            as(scopedBiller());
            mockMvc.perform(post(INVOICES_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(workorderInvoiceBody(SHOP_B)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.correlationId").exists())
                    .andExpect(header().exists("X-Correlation-Id"))
                    .andExpect(jsonPath("$.message")
                            .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SHOP_B.toString()))));

            verify(invoiceService, never()).createInvoice(any(InvoiceCreationRequest.class));
        }

        @Test
        @DisplayName("scoped caller naming a location the replica does not hold answers 403 (fail closed)")
        void unknownLocationDenies() throws Exception {
            as(scopedBiller());
            mockMvc.perform(post(INVOICES_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(workorderInvoiceBody(UNKNOWN_LOCATION)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(invoiceService, never()).createInvoice(any(InvoiceCreationRequest.class));
        }

        @Test
        @DisplayName("scoped caller omitting locationId fails closed with 403")
        void locationlessRequestDeniesScopedCaller() throws Exception {
            as(scopedBiller());
            mockMvc.perform(post(INVOICES_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(workorderInvoiceBody(null)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(invoiceService, never()).createInvoice(any(InvoiceCreationRequest.class));
        }

        @Test
        @DisplayName("pre-rollout token without loc_* claims keeps today's behaviour: 201 for any location")
        void preRolloutTokenIsUnchanged() throws Exception {
            stubCreateInvoice();

            as(preRolloutBiller());
            mockMvc.perform(post(INVOICES_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(workorderInvoiceBody(SHOP_B)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("pre-rollout token omitting locationId is still accepted")
        void preRolloutTokenWithoutLocationIsUnchanged() throws Exception {
            stubCreateInvoice();

            as(preRolloutBiller());
            mockMvc.perform(post(INVOICES_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(workorderInvoiceBody(null)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("caller whose invoice:manage is global answers 201 for a location outside its nodes")
        void globalGrantIsNotLocationChecked() throws Exception {
            stubCreateInvoice();

            as(globalBiller());
            mockMvc.perform(post(INVOICES_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(workorderInvoiceBody(SHOP_B)))
                    .andExpect(status().isCreated());
        }
    }

    // ---------------------------------------------------------------------------------------
    // POST /v1/invoices/from-order
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("createInvoiceFromOrder")
    class CreateInvoiceFromOrder {

        @Test
        @DisplayName("scoped caller fronting an order at a location in reach answers 201")
        void scopedCallerInReach() throws Exception {
            stubCreateFromOrder();

            as(scopedBiller());
            mockMvc.perform(post(FROM_ORDER_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderInvoiceBody(SHOP_A)))
                    .andExpect(status().isCreated());

            verify(orderInvoiceService).createInvoiceForOrder(any(OrderInvoiceCreationRequest.class));
        }

        @Test
        @DisplayName("scoped caller fronting an order at a location out of reach answers 403 LOCATION_SCOPE_DENIED")
        void scopedCallerOutOfReach() throws Exception {
            as(scopedBiller());
            mockMvc.perform(post(FROM_ORDER_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderInvoiceBody(SHOP_B)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                    .andExpect(jsonPath("$.correlationId").exists());

            verify(orderInvoiceService, never()).createInvoiceForOrder(any(OrderInvoiceCreationRequest.class));
        }

        @Test
        @DisplayName("scoped caller omitting locationId fails closed with 403")
        void locationlessRequestDeniesScopedCaller() throws Exception {
            as(scopedBiller());
            mockMvc.perform(post(FROM_ORDER_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderInvoiceBody(null)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

            verify(orderInvoiceService, never()).createInvoiceForOrder(any(OrderInvoiceCreationRequest.class));
        }

        @Test
        @DisplayName("pre-rollout token without loc_* claims keeps today's behaviour: 201 for any location")
        void preRolloutTokenIsUnchanged() throws Exception {
            stubCreateFromOrder();

            as(preRolloutBiller());
            mockMvc.perform(post(FROM_ORDER_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderInvoiceBody(SHOP_B)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("caller whose invoice:manage is global answers 201 for a location outside its nodes")
        void globalGrantIsNotLocationChecked() throws Exception {
            stubCreateFromOrder();

            as(globalBiller());
            mockMvc.perform(post(FROM_ORDER_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderInvoiceBody(SHOP_B)))
                    .andExpect(status().isCreated());
        }
    }

    // ---------------------------------------------------------------------------------------
    // GET /v1/invoices/{invoiceId} — the gate itself lives in InvoiceServiceImpl (after the 404);
    // here the service is a mock and the slice proves its denial reaches the client intact.
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("getInvoice")
    class GetInvoice {

        @Test
        @DisplayName("an invoice the service lets through answers 200")
        void inReach() throws Exception {
            InvoiceDetailsResponse detail = new InvoiceDetailsResponse();
            detail.setInvoiceId(INVOICE_ID);
            when(invoiceService.getInvoice(INVOICE_ID)).thenReturn(detail);

            as(scopedBiller());
            mockMvc.perform(get(INVOICES_URL + "/{invoiceId}", INVOICE_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.invoiceId").value(INVOICE_ID.toString()));
        }

        @Test
        @DisplayName("a service-side scope denial answers 403 LOCATION_SCOPE_DENIED, not the module's FORBIDDEN")
        void outOfReach() throws Exception {
            when(invoiceService.getInvoice(INVOICE_ID))
                    .thenThrow(new LocationScopeDeniedException(InvoicePermissions.VIEW, SHOP_B.toString()));

            as(scopedBiller());
            mockMvc.perform(get(INVOICES_URL + "/{invoiceId}", INVOICE_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                    .andExpect(jsonPath("$.correlationId").exists())
                    .andExpect(header().exists("X-Correlation-Id"));
        }

        @Test
        @DisplayName("missing invoice stays 404 for a scoped caller — existence is checked before scope")
        void missingStays404() throws Exception {
            when(invoiceService.getInvoice(INVOICE_ID)).thenThrow(new InvoiceNotFoundException(INVOICE_ID));

            as(scopedBiller());
            mockMvc.perform(get(INVOICES_URL + "/{invoiceId}", INVOICE_ID)).andExpect(status().isNotFound());
        }
    }

    /** Fixed clock for both advices, plus method security so {@code @PreAuthorize} is enforced. */
    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }
    }
}
