package com.positivity.supplier.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.supplier.internal.config.SecurityConfig;
import com.positivity.supplier.internal.exception.SupplierConflictException;
import com.positivity.supplier.internal.exception.SupplierForbiddenException;
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.exception.VendorTaxIdUnreadableException;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.internal.service.model.PagedResponse;
import com.positivity.supplier.internal.vendor.service.SupplierVendorService;
import com.positivity.supplier.internal.vendor.service.VendorTaxIdRevealService;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealView;
import com.positivity.supplier.internal.vendor.service.model.TaxRegistrationView;
import com.positivity.supplier.internal.vendor.service.model.VendorCreateRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorFactReplayResult;
import com.positivity.supplier.internal.vendor.service.model.VendorStatus;
import com.positivity.supplier.internal.vendor.service.model.VendorView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Web contract of {@link SupplierVendorController} (#2516): every {@code @PreAuthorize} (403 without
 * the authority, 2xx with it, 401 unauthenticated), the domain error codes the story names, and the
 * request records' 400s.
 */
@WebMvcTest(controllers = SupplierVendorController.class)
@Import({SecurityConfig.class, SupplierVendorControllerWebMvcTest.FixedClockConfig.class})
class SupplierVendorControllerWebMvcTest {

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);
        }

        /** See {@code SupplierAdminControllersWebMvcTest.FixedClockConfig}: the filter runs only in the chain. */
        @Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<
                        com.positivity.security.common.GatewayAuthoritiesFilter>
                gatewayAuthoritiesFilterRegistration(
                        com.positivity.security.common.GatewayAuthoritiesFilter gatewayAuthoritiesFilter) {
            var registration =
                    new org.springframework.boot.web.servlet.FilterRegistrationBean<>(gatewayAuthoritiesFilter);
            registration.setEnabled(false);
            return registration;
        }
    }

    private static final String BASE = "/v1/supplier/vendors";
    private static final UUID VENDOR_ID = UUID.fromString("018f0000-0000-7000-8000-000000000601");
    private static final UUID CHANGE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000602");
    private static final UUID REGISTRATION_ID = UUID.fromString("018f0000-0000-7000-8000-000000000603");
    private static final String FAKE_NUMBER = "000-00-1234";
    private static final String REVEAL = """
            {"reason":"Verifying W-9 received 2026-10-08"}
            """;

    private static final String CREATE = """
            {"legalName":"Michelin North America, Inc.","displayName":"Michelin",
             "defaultPaymentTerms":"NET30","defaultCurrency":"USD"}
            """;
    private static final String UPDATE = """
            {"legalName":"Michelin North America, Inc.","displayName":"Michelin",
             "defaultPaymentTerms":"NET45","defaultCurrency":"USD","version":0}
            """;
    private static final String STATUS = """
            {"reason":"Merged into another vendor"}
            """;
    private static final String REMIT_CHANGE = """
            {"remitTo":{"payeeName":"Michelin NA","addressLine1":"PO Box 200","city":"Greenville",
                        "region":"SC","postalCode":"29615","countryCode":"US"},
             "reason":"Vendor letter gives a new lockbox"}
            """;
    private static final String APPROVAL = """
            {"verificationNote":"Called the AR line on file"}
            """;
    private static final String REJECTION = """
            {"note":"AR line knows nothing of it"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupplierVendorService vendorService;

    @MockitoBean
    private VendorTaxIdRevealService taxIdRevealService;

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String... authorities) {
        return builder.header("X-User", "web-mvc-tester").header("X-Authorities", String.join(",", authorities));
    }

    private static VendorView view() {
        return new VendorView(
                VENDOR_ID,
                "V-000001",
                "Michelin North America, Inc.",
                "Michelin",
                List.of(),
                null,
                0,
                null,
                null,
                null,
                "NET30",
                "USD",
                VendorStatus.ACTIVE,
                null,
                null,
                Instant.parse("2026-10-05T12:00:00Z"),
                "clerk.a",
                Instant.parse("2026-10-05T12:00:00Z"),
                "clerk.a",
                0L);
    }

    static Stream<Arguments> permissionTable() {
        String vendor = BASE + "/" + VENDOR_ID;
        String change = vendor + "/remit-to-changes/" + CHANGE_ID;
        return Stream.of(
                Arguments.of("GET", BASE, null, SupplierPermissions.VENDOR_READ),
                Arguments.of("GET", vendor, null, SupplierPermissions.VENDOR_READ),
                Arguments.of("GET", vendor + "/remit-to-changes", null, SupplierPermissions.VENDOR_READ),
                Arguments.of("POST", BASE, CREATE, SupplierPermissions.VENDOR_WRITE),
                Arguments.of("PUT", vendor, UPDATE, SupplierPermissions.VENDOR_WRITE),
                Arguments.of("POST", vendor + "/deactivation", STATUS, SupplierPermissions.VENDOR_WRITE),
                Arguments.of("POST", vendor + "/reactivation", STATUS, SupplierPermissions.VENDOR_WRITE),
                Arguments.of("POST", vendor + "/remit-to-changes", REMIT_CHANGE, SupplierPermissions.VENDOR_WRITE),
                Arguments.of("POST", change + "/approval", APPROVAL, SupplierPermissions.VENDOR_REMIT_APPROVE),
                Arguments.of("POST", change + "/rejection", REJECTION, SupplierPermissions.VENDOR_REMIT_APPROVE),
                Arguments.of("POST", BASE + "/facts/replay", null, SupplierPermissions.FACT_REPLAY),
                Arguments.of(
                        "POST",
                        vendor + "/tax-registrations/" + REGISTRATION_ID + "/reveal",
                        REVEAL,
                        SupplierPermissions.VENDOR_TAX_ID_REVEAL),
                Arguments.of("GET", vendor + "/tax-id-reveals", null, SupplierPermissions.AUDIT_READ));
    }

    private static MockHttpServletRequestBuilder request(String method, String path, String body) {
        MockHttpServletRequestBuilder builder =
                switch (method) {
                    case "GET" -> get(path);
                    case "POST" -> post(path);
                    case "PUT" -> put(path);
                    default -> throw new IllegalArgumentException(method);
                };
        if (body != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return builder;
    }

    @ParameterizedTest(name = "{0} {1} requires {3}")
    @MethodSource("permissionTable")
    void missingAuthorityIsRejectedWith403(String method, String path, String body, String permission)
            throws Exception {
        mockMvc.perform(authed(request(method, path, body), "supplier:profile:write"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(vendorService);
        verifyNoInteractions(taxIdRevealService);
    }

    @ParameterizedTest(name = "{0} {1} passes with {3}")
    @MethodSource("permissionTable")
    void grantedAuthorityIsAccepted(String method, String path, String body, String permission) throws Exception {
        mockMvc.perform(authed(request(method, path, body), permission))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("%s %s with %s must reach the controller", method, path, permission)
                        .isBetween(200, 299));
    }

    @ParameterizedTest(name = "{0} {1} unauthenticated is 401")
    @MethodSource("permissionTable")
    void unauthenticatedIsRejectedWith401(String method, String path, String body, String permission) throws Exception {
        mockMvc.perform(request(method, path, body)).andExpect(status().isUnauthorized());
    }

    @Test
    void createWithoutNumberIs201AndDeserializesTheRequest() throws Exception {
        when(vendorService.createVendor(any())).thenReturn(view());

        mockMvc.perform(authed(
                        post(BASE).contentType(MediaType.APPLICATION_JSON).content(CREATE),
                        SupplierPermissions.VENDOR_WRITE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vendorNumber").value("V-000001"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        ArgumentCaptor<VendorCreateRequest> captor = ArgumentCaptor.captor();
        verify(vendorService).createVendor(captor.capture());
        assertThat(captor.getValue().vendorNumber()).isNull();
        assertThat(captor.getValue().taxRegistrations()).isEmpty();
    }

    @Test
    void malformedFieldsAre400ValidationError() throws Exception {
        mockMvc.perform(authed(
                        post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                                        {"vendorNumber":"lower-case","legalName":"L","displayName":"D",
                                         "defaultPaymentTerms":"NET30","defaultCurrency":"USD"}
                                        """),
                        SupplierPermissions.VENDOR_WRITE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(authed(
                        post(BASE + "/" + VENDOR_ID + "/deactivation")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"short\"}"),
                        SupplierPermissions.VENDOR_WRITE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(authed(
                        post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                                        {"legalName":"L","displayName":"D",
                                         "defaultPaymentTerms":"NET121","defaultCurrency":"USD"}
                                        """),
                        SupplierPermissions.VENDOR_WRITE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void domainCodesMapToTheirStatuses() throws Exception {
        when(vendorService.createVendor(any()))
                .thenThrow(new SupplierConflictException(SupplierConflictException.VENDOR_NUMBER_TAKEN, "taken"));
        when(vendorService.getVendor(VENDOR_ID))
                .thenThrow(new SupplierNotFoundException(SupplierNotFoundException.VENDOR_NOT_FOUND, "missing"));
        when(vendorService.approveRemitChange(eq(VENDOR_ID), eq(CHANGE_ID), any()))
                .thenThrow(new SupplierForbiddenException(
                        SupplierForbiddenException.VENDOR_REMIT_SELF_APPROVAL, "second person"));
        when(vendorService.rejectRemitChange(eq(VENDOR_ID), eq(CHANGE_ID), any()))
                .thenThrow(new SupplierConflictException(
                        SupplierConflictException.VENDOR_REMIT_CHANGE_NOT_PENDING, "decided"));
        when(vendorService.requestRemitChange(eq(VENDOR_ID), any()))
                .thenThrow(new SupplierConflictException(
                        SupplierConflictException.VENDOR_REMIT_CHANGE_PENDING, "pending"));
        when(vendorService.updateVendor(eq(VENDOR_ID), any()))
                .thenThrow(new SupplierConflictException(SupplierConflictException.CONFLICT, "stale"));

        mockMvc.perform(authed(
                        post(BASE).contentType(MediaType.APPLICATION_JSON).content(CREATE),
                        SupplierPermissions.VENDOR_WRITE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUPPLIER_VENDOR_NUMBER_TAKEN"));
        mockMvc.perform(authed(get(BASE + "/" + VENDOR_ID), SupplierPermissions.VENDOR_READ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPLIER_VENDOR_NOT_FOUND"));
        mockMvc.perform(authed(
                        post(BASE + "/" + VENDOR_ID + "/remit-to-changes/" + CHANGE_ID + "/approval")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(APPROVAL),
                        SupplierPermissions.VENDOR_REMIT_APPROVE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SUPPLIER_VENDOR_REMIT_SELF_APPROVAL"));
        mockMvc.perform(authed(
                        post(BASE + "/" + VENDOR_ID + "/remit-to-changes/" + CHANGE_ID + "/rejection")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REJECTION),
                        SupplierPermissions.VENDOR_REMIT_APPROVE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUPPLIER_VENDOR_REMIT_CHANGE_NOT_PENDING"));
        mockMvc.perform(authed(
                        post(BASE + "/" + VENDOR_ID + "/remit-to-changes")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REMIT_CHANGE),
                        SupplierPermissions.VENDOR_WRITE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUPPLIER_VENDOR_REMIT_CHANGE_PENDING"));
        mockMvc.perform(authed(
                        put(BASE + "/" + VENDOR_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(UPDATE),
                        SupplierPermissions.VENDOR_WRITE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void listAndReplayPassTheirParameters() throws Exception {
        when(vendorService.listVendors("mich", VendorStatus.ACTIVE, 1, 20))
                .thenReturn(new PagedResponse<>(List.of(view()), 1, 20, 21, 2));
        when(vendorService.replayFacts(VENDOR_ID, 50))
                .thenReturn(new VendorFactReplayResult(50, CHANGE_ID, false, Instant.parse("2026-10-05T12:00:00Z")));

        mockMvc.perform(authed(
                        get(BASE)
                                .param("q", "mich")
                                .param("status", "ACTIVE")
                                .param("page", "1")
                                .param("size", "20"),
                        SupplierPermissions.VENDOR_READ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].vendorId").value(VENDOR_ID.toString()))
                .andExpect(jsonPath("$.totalElements").value(21));
        mockMvc.perform(authed(
                        post(BASE + "/facts/replay")
                                .param("afterVendorId", VENDOR_ID.toString())
                                .param("limit", "50"),
                        SupplierPermissions.FACT_REPLAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emitted").value(50))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.nextAfterVendorId").value(CHANGE_ID.toString()));
    }

    // ── #2621: masked reads and the reveal ──────────────────────────────────────────────

    @Test
    @DisplayName("#2621 AC 8: a vendor's registrations serialise as {registrationId, scheme, region, last4}")
    void viewIsMasked() throws Exception {
        VendorView base = view();
        VendorView masked = new VendorView(
                base.vendorId(),
                base.vendorNumber(),
                base.legalName(),
                base.displayName(),
                List.of(new TaxRegistrationView(REGISTRATION_ID, "SSN", null, "1234")),
                null,
                0,
                null,
                null,
                null,
                "NET30",
                "USD",
                VendorStatus.ACTIVE,
                null,
                null,
                base.createdAt(),
                base.createdBy(),
                base.updatedAt(),
                base.updatedBy(),
                0L);
        when(vendorService.getVendor(VENDOR_ID)).thenReturn(masked);

        mockMvc.perform(authed(get(BASE + "/" + VENDOR_ID), SupplierPermissions.VENDOR_READ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taxRegistrations[0].registrationId").value(REGISTRATION_ID.toString()))
                .andExpect(jsonPath("$.taxRegistrations[0].last4").value("1234"))
                .andExpect(jsonPath("$.taxRegistrations[0].number").doesNotExist());
    }

    @Test
    @DisplayName("#2621 AC 11: the reveal answers 200 with the number and Cache-Control: no-store")
    void revealIsNoStore() throws Exception {
        when(taxIdRevealService.reveal(eq(VENDOR_ID), eq(REGISTRATION_ID), any()))
                .thenReturn(new TaxIdRevealView(REGISTRATION_ID, "SSN", null, FAKE_NUMBER));

        mockMvc.perform(authed(
                        post(BASE + "/" + VENDOR_ID + "/tax-registrations/" + REGISTRATION_ID + "/reveal")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REVEAL),
                        SupplierPermissions.VENDOR_TAX_ID_REVEAL))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.number").value(FAKE_NUMBER));
    }

    @Test
    @DisplayName("#2621 AC 12: a 9-character reason is 400 JUSTIFICATION_REQUIRED and the service is never called")
    void shortReasonIsJustificationRequired() throws Exception {
        mockMvc.perform(authed(
                        post(BASE + "/" + VENDOR_ID + "/tax-registrations/" + REGISTRATION_ID + "/reveal")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"too short\"}"),
                        SupplierPermissions.VENDOR_TAX_ID_REVEAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("JUSTIFICATION_REQUIRED"));
        verifyNoInteractions(taxIdRevealService);
    }

    @Test
    @DisplayName("#2621: an unknown registration is 404, an unreadable number 500, neither carries a number")
    void revealErrorCodes() throws Exception {
        UUID unknown = UUID.fromString("018f0000-0000-7000-8000-000000000604");
        when(taxIdRevealService.reveal(eq(VENDOR_ID), eq(unknown), any()))
                .thenThrow(new SupplierNotFoundException(
                        SupplierNotFoundException.VENDOR_TAX_REGISTRATION_NOT_FOUND, "missing"));
        when(taxIdRevealService.reveal(eq(VENDOR_ID), eq(REGISTRATION_ID), any()))
                .thenThrow(new VendorTaxIdUnreadableException(
                        "AUTHENTICATION_FAILED", "k1", "Vendor tax-registration number failed authentication", null));

        mockMvc.perform(authed(
                        post(BASE + "/" + VENDOR_ID + "/tax-registrations/" + unknown + "/reveal")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REVEAL),
                        SupplierPermissions.VENDOR_TAX_ID_REVEAL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPLIER_VENDOR_TAX_REGISTRATION_NOT_FOUND"));
        mockMvc.perform(authed(
                        post(BASE + "/" + VENDOR_ID + "/tax-registrations/" + REGISTRATION_ID + "/reveal")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REVEAL),
                        SupplierPermissions.VENDOR_TAX_ID_REVEAL))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SUPPLIER_VENDOR_TAX_ID_UNREADABLE"))
                .andExpect(jsonPath("$.number").doesNotExist());
    }

    @Test
    @DisplayName("#2621 AC 10: a 65-character number is 400 and the response body does not echo it")
    void overlongNumberIsNotEchoed() throws Exception {
        String overlong = "FAKE" + "7".repeat(61);
        String body = mockMvc.perform(authed(
                        post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                                        {"legalName":"L","displayName":"D","defaultPaymentTerms":"NET30",
                                         "defaultCurrency":"USD","taxRegistrations":[{"scheme":"EIN","number":"%s"}]}
                                        """.formatted(overlong)),
                        SupplierPermissions.VENDOR_WRITE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body.contains("FAKE7777"))
                .as("number absent from the error body")
                .isFalse();
        verifyNoInteractions(vendorService);
    }

    @Test
    @DisplayName("#2621 AC 20: a misshapen scheme is 400 VALIDATION_ERROR on its field and the body does not echo it")
    void misshapenSchemeIsNotEchoed() throws Exception {
        when(vendorService.createVendor(any()))
                .thenThrow(new com.positivity.supplier.internal.exception.SupplierValidationException(
                        "VALIDATION_ERROR",
                        "Tax registration refused: taxRegistrations[0].scheme must be letters",
                        List.of(new com.positivity.shared.error.ApiError.FieldError(
                                "taxRegistrations[0].scheme", "must be letters"))));
        String body = mockMvc.perform(authed(
                        post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                                        {"legalName":"L","displayName":"D","defaultPaymentTerms":"NET30",
                                         "defaultCurrency":"USD",
                                         "taxRegistrations":[{"scheme":"EIN123","number":"000-00-1234"}]}
                                        """),
                        SupplierPermissions.VENDOR_WRITE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("taxRegistrations[0].scheme"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body.contains("EIN123") || body.contains("000-00-1234"))
                .as("submitted value absent from the error body")
                .isFalse();
    }
}
