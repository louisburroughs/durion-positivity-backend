package com.positivity.supplier.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.supplier.internal.config.SecurityConfig;
import com.positivity.supplier.internal.exception.SupplierConflictException;
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.exception.SupplierValidationException;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.service.SupplierProfileAdminService;
import com.positivity.supplier.service.model.AuthConfigRequest;
import com.positivity.supplier.service.model.AuthConfigView;
import com.positivity.supplier.service.model.CommercialAccountView;
import com.positivity.supplier.service.model.EndpointBindingRequest;
import com.positivity.supplier.service.model.EndpointBindingView;
import com.positivity.supplier.service.model.ProfileSourceOfTruth;
import com.positivity.supplier.service.model.SupplierAccountRole;
import com.positivity.supplier.service.model.SupplierAuthType;
import com.positivity.supplier.service.model.VendorProfileRequest;
import com.positivity.supplier.service.model.VendorProfileView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
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
 * Web-layer contract of the four pos-supplier admin controllers
 * (durion-positivity-backend#1222) over real HTTP semantics, mirroring the pos-warranty
 * controller-test pattern: Jackson round-trips of the {@code service.model} contract records,
 * {@code SupplierExceptionHandler} mapping (404/400/409 with domain codes per ADR-0017,
 * correlation-id echo and generated fallback), and — via the imported production
 * {@link SecurityConfig}/{@code GatewaySecurityConfig} chain — deny-by-default: every endpoint
 * is exercised with 403 (missing authority, {@code FORBIDDEN} envelope), 2xx (granted), and
 * 401 (unauthenticated).
 */
@WebMvcTest(
        controllers = {
            SupplierProfileAdminController.class,
            SupplierAuthConfigAdminController.class,
            SupplierAccountAdminController.class,
            SupplierEndpointBindingAdminController.class
        })
@Import({SecurityConfig.class, SupplierAdminControllersWebMvcTest.FixedClockConfig.class})
class SupplierAdminControllersWebMvcTest {

    /** TimeConfig-style Clock is not auto-configured in the slice; the advice needs one. */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC);
        }

        /**
         * The MockMvc auto-configuration registers every {@code Filter} bean directly, which
         * would run {@code GatewayAuthoritiesFilter} (a {@code OncePerRequestFilter}) BEFORE
         * the security chain and mark it already-filtered — the in-chain instance then skips,
         * and {@code SecurityContextHolderFilter} wipes the authentication, failing every
         * request with 401. Disable the direct registration so the filter runs exactly where
         * production runs it: inside {@code gatewaySecurityFilterChain}.
         */
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

    private static final UUID PROFILE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000501");
    private static final UUID CHILD_ID = UUID.fromString("018f0000-0000-7000-8000-000000000502");
    private static final String BASE = "/supplier/admin/profiles";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupplierProfileAdminService adminService;

    // ------------------------------------------------------------------ helpers

    /** Authenticates the request the way the API gateway does: X-Authorities / X-User headers. */
    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String... authorities) {
        return builder.header("X-User", "web-mvc-tester").header("X-Authorities", String.join(",", authorities));
    }

    private static VendorProfileView profileView() {
        return new VendorProfileView(
                PROFILE_ID, "michelin-eu", "Michelin EU", true, false, ProfileSourceOfTruth.ADMIN, 5000, 30000, 3);
    }

    private static String profileJson() {
        return """
                {"supplierRef": "michelin-eu", "displayName": "Michelin EU", "enabled": true,
                 "sandbox": false, "connectTimeoutMillis": 5000, "readTimeoutMillis": 30000,
                 "maxRetries": 3}
                """;
    }

    private static String authConfigJson() {
        return """
                {"name": "basic-apikey", "type": "BASIC_PLUS_APIKEY", "usernameRef": "env:U",
                 "passwordRef": "env:P", "apiKeyRef": "env:K", "apiKeyHeader": "x-api-key"}
                """;
    }

    private static String bindingJson(String capability) {
        return """
                {"capability": "%s", "protocolFamily": "EDIWHEEL_A25", "version": "A2_5",
                 "baseUrl": "https://api.example", "path": "/inquiry",
                 "authConfigName": "s2s-bearer", "enabled": true}
                """.formatted(capability);
    }

    // ------------------------------------------------------------------ happy-path round trips

    @Nested
    class HappyPathJsonRoundTrips {

        @Test
        void createProfileDeserializesTheContractRecordAndSerializesTheView() throws Exception {
            when(adminService.createProfile(any())).thenReturn(profileView());

            mockMvc.perform(authed(
                            post(BASE).contentType(MediaType.APPLICATION_JSON).content(profileJson()),
                            SupplierPermissions.PROFILE_WRITE))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.vendorProfileId").value(PROFILE_ID.toString()))
                    .andExpect(jsonPath("$.supplierRef").value("michelin-eu"))
                    .andExpect(jsonPath("$.sourceOfTruth").value("ADMIN"))
                    .andExpect(jsonPath("$.connectTimeoutMillis").value(5000));

            ArgumentCaptor<VendorProfileRequest> captor = ArgumentCaptor.captor();
            verify(adminService).createProfile(captor.capture());
            VendorProfileRequest deserialized = captor.getValue();
            assertThat(deserialized.supplierRef()).isEqualTo("michelin-eu");
            assertThat(deserialized.displayName()).isEqualTo("Michelin EU");
            assertThat(deserialized.enabled()).isTrue();
            assertThat(deserialized.maxRetries()).isEqualTo(3);
        }

        @Test
        void getAndListProfilesSerializeTheView() throws Exception {
            when(adminService.getProfile(PROFILE_ID)).thenReturn(profileView());
            when(adminService.listProfiles()).thenReturn(List.of(profileView()));

            mockMvc.perform(authed(get(BASE + "/{id}", PROFILE_ID), SupplierPermissions.PROFILE_READ))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.supplierRef").value("michelin-eu"));
            mockMvc.perform(authed(get(BASE), SupplierPermissions.PROFILE_READ))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].vendorProfileId").value(PROFILE_ID.toString()));
        }

        @Test
        void deleteProfileIs204NoContent() throws Exception {
            mockMvc.perform(authed(delete(BASE + "/{id}", PROFILE_ID), SupplierPermissions.PROFILE_WRITE))
                    .andExpect(status().isNoContent());

            verify(adminService).deleteProfile(PROFILE_ID);
        }

        @Test
        void authConfigCreateRoundTripsApiKeyHeaderAndNeverSerializesSecretReferences() throws Exception {
            when(adminService.createAuthConfig(eq(PROFILE_ID), any()))
                    .thenReturn(new AuthConfigView(
                            CHILD_ID, "basic-apikey", SupplierAuthType.BASIC_PLUS_APIKEY, "x-api-key"));

            String body = mockMvc.perform(authed(
                            post(BASE + "/{id}/auth-configs", PROFILE_ID)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(authConfigJson()),
                            SupplierPermissions.PROFILE_WRITE))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.authConfigId").value(CHILD_ID.toString()))
                    .andExpect(jsonPath("$.name").value("basic-apikey"))
                    .andExpect(jsonPath("$.type").value("BASIC_PLUS_APIKEY"))
                    .andExpect(jsonPath("$.apiKeyHeader").value("x-api-key"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            // ADR-0050 §4 carry-forward: secret references are write-only. The view record has
            // no *Ref components, so no reference key may ever appear in a response body.
            assertThat(body).doesNotContain("Ref");

            ArgumentCaptor<AuthConfigRequest> captor = ArgumentCaptor.captor();
            verify(adminService).createAuthConfig(eq(PROFILE_ID), captor.capture());
            AuthConfigRequest deserialized = captor.getValue();
            assertThat(deserialized.type()).isEqualTo(SupplierAuthType.BASIC_PLUS_APIKEY);
            assertThat(deserialized.apiKeyRef()).isEqualTo("env:K");
            assertThat(deserialized.apiKeyHeader()).isEqualTo("x-api-key");
        }

        @Test
        void accountListSerializesTheAccountView() throws Exception {
            when(adminService.listAccounts(PROFILE_ID))
                    .thenReturn(List.of(new CommercialAccountView(
                            CHILD_ID, SupplierAccountRole.BILLING, "0000012345", "31", null)));

            mockMvc.perform(authed(get(BASE + "/{id}/accounts", PROFILE_ID), SupplierPermissions.PROFILE_READ))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].role").value("BILLING"))
                    .andExpect(jsonPath("$[0].accountNumber").value("0000012345"));
        }

        @Test
        void bindingCreateRoundTripsCanonicalStringKeys() throws Exception {
            when(adminService.createBinding(eq(PROFILE_ID), any()))
                    .thenReturn(new EndpointBindingView(
                            CHILD_ID,
                            "STOCK_INQUIRY",
                            "EDIWHEEL_A25",
                            "A2_5",
                            "https://api.example",
                            "/inquiry",
                            "s2s-bearer",
                            null,
                            true,
                            null));

            mockMvc.perform(authed(
                            post(BASE + "/{id}/bindings", PROFILE_ID)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(bindingJson("STOCK_INQUIRY")),
                            SupplierPermissions.PROFILE_WRITE))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.capability").value("STOCK_INQUIRY"))
                    .andExpect(jsonPath("$.protocolFamily").value("EDIWHEEL_A25"))
                    .andExpect(jsonPath("$.version").value("A2_5"))
                    .andExpect(jsonPath("$.authConfigName").value("s2s-bearer"));

            ArgumentCaptor<EndpointBindingRequest> captor = ArgumentCaptor.captor();
            verify(adminService).createBinding(eq(PROFILE_ID), captor.capture());
            assertThat(captor.getValue().capability()).isEqualTo("STOCK_INQUIRY");
            assertThat(captor.getValue().enabled()).isTrue();
        }
    }

    // ------------------------------------------------------------------ ApiError envelope (ADR-0017)

    @Nested
    class ErrorEnvelope {

        @Test
        void unknownProfileIs404WithTheDomainCodeAndEchoedCorrelationId() throws Exception {
            when(adminService.getProfile(PROFILE_ID))
                    .thenThrow(new SupplierNotFoundException(
                            SupplierNotFoundException.PROFILE_NOT_FOUND,
                            "Vendor profile " + PROFILE_ID + " does not exist"));

            mockMvc.perform(authed(get(BASE + "/{id}", PROFILE_ID), SupplierPermissions.PROFILE_READ)
                            .header(CORRELATION_HEADER, "corr-317"))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string(CORRELATION_HEADER, "corr-317"))
                    .andExpect(jsonPath("$.code").value("SUPPLIER_PROFILE_NOT_FOUND"))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.timestamp").value("2026-08-11T12:00:00Z"))
                    .andExpect(jsonPath("$.correlationId").value("corr-317"));
        }

        @Test
        void missingCorrelationHeaderGetsAGeneratedIdEchoedInHeaderAndBody() throws Exception {
            when(adminService.getProfile(PROFILE_ID))
                    .thenThrow(new SupplierNotFoundException(
                            SupplierNotFoundException.PROFILE_NOT_FOUND, "missing"));

            var result = mockMvc.perform(authed(get(BASE + "/{id}", PROFILE_ID), SupplierPermissions.PROFILE_READ))
                    .andExpect(status().isNotFound())
                    .andReturn();

            String headerValue = result.getResponse().getHeader(CORRELATION_HEADER);
            assertThat(headerValue).isNotBlank();
            assertThat(result.getResponse().getContentAsString()).contains("\"correlationId\":\"" + headerValue + "\"");
        }

        @Test
        void yamlManagedMutationIs409WithTheDomainCode() throws Exception {
            when(adminService.updateProfile(eq(PROFILE_ID), any()))
                    .thenThrow(new SupplierConflictException(
                            SupplierConflictException.PROFILE_YAML_MANAGED,
                            "Vendor profile 'michelin-eu' is YAML-managed (ADR-0050 §6)"));

            mockMvc.perform(authed(
                            put(BASE + "/{id}", PROFILE_ID)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(profileJson()),
                            SupplierPermissions.PROFILE_WRITE))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SUPPLIER_PROFILE_YAML_MANAGED"))
                    .andExpect(jsonPath("$.status").value(409));
        }

        /**
         * Carry-forward from the slice-1 review: an unknown canonical capability string is a
         * deterministic 400 with a typed code at the HTTP boundary, never a 500.
         */
        @Test
        void unknownCapabilityKeySurfacesAsDeterministic400() throws Exception {
            when(adminService.createBinding(eq(PROFILE_ID), any()))
                    .thenThrow(new SupplierValidationException(
                            SupplierValidationException.UNKNOWN_CAPABILITY,
                            "'NOT_A_CAPABILITY' is not a canonical supplier capability key"));

            mockMvc.perform(authed(
                            post(BASE + "/{id}/bindings", PROFILE_ID)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(bindingJson("NOT_A_CAPABILITY")),
                            SupplierPermissions.PROFILE_WRITE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SUPPLIER_UNKNOWN_CAPABILITY"))
                    .andExpect(jsonPath("$.message")
                            .value("'NOT_A_CAPABILITY' is not a canonical supplier capability key"));
        }

        /**
         * The vendor profile rows are {@code @Version}-ed; a lost concurrent update is a
         * retryable 409 CONFLICT with the standard envelope — never the 500 catch-all.
         */
        @Test
        void concurrentUpdateSurfacesAs409ConflictNotInternalError() throws Exception {
            when(adminService.updateProfile(eq(PROFILE_ID), any()))
                    .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
                            "SupplierProfileEntity", PROFILE_ID));

            mockMvc.perform(authed(
                            put(BASE + "/{id}", PROFILE_ID)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(profileJson()),
                            SupplierPermissions.PROFILE_WRITE)
                            .header(CORRELATION_HEADER, "corr-409"))
                    .andExpect(status().isConflict())
                    .andExpect(header().string(CORRELATION_HEADER, "corr-409"))
                    .andExpect(jsonPath("$.code").value("CONFLICT"))
                    .andExpect(jsonPath("$.message").value("Resource was updated concurrently. Please retry."))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.correlationId").value("corr-409"));
        }

        @Test
        void contractRecordConstructorViolationIs400ValidationErrorWithTheConstructorMessage() throws Exception {
            mockMvc.perform(authed(
                            post(BASE)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"supplierRef\": \" \", \"displayName\": \"Michelin EU\","
                                            + " \"enabled\": true, \"sandbox\": false}"),
                            SupplierPermissions.PROFILE_WRITE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.message").value("supplierRef must not be blank"));
        }

        @Test
        void malformedJsonIs400MalformedRequest() throws Exception {
            mockMvc.perform(authed(
                            post(BASE).contentType(MediaType.APPLICATION_JSON).content("{not json"),
                            SupplierPermissions.PROFILE_WRITE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
    }

    // ------------------------------------------------------------------ permission table (ADR-0040)

    /**
     * Every admin endpoint with its required authority. Bodies must be VALID: body
     * deserialization runs before {@code @PreAuthorize}, so an invalid body would 400 and mask
     * the security assertion.
     */
    static Stream<Arguments> permissionTable() {
        String profile = profileJson();
        String authConfig = authConfigJson();
        String account = "{\"role\": \"BILLING\", \"accountNumber\": \"0000012345\"}";
        String binding = bindingJson("STOCK_INQUIRY");
        String profilePath = BASE + "/" + PROFILE_ID;
        return Stream.of(
                Arguments.of("GET", BASE, null, SupplierPermissions.PROFILE_READ),
                Arguments.of("GET", profilePath, null, SupplierPermissions.PROFILE_READ),
                Arguments.of("POST", BASE, profile, SupplierPermissions.PROFILE_WRITE),
                Arguments.of("PUT", profilePath, profile, SupplierPermissions.PROFILE_WRITE),
                Arguments.of("DELETE", profilePath, null, SupplierPermissions.PROFILE_WRITE),
                Arguments.of("GET", profilePath + "/auth-configs", null, SupplierPermissions.PROFILE_READ),
                Arguments.of("POST", profilePath + "/auth-configs", authConfig, SupplierPermissions.PROFILE_WRITE),
                Arguments.of(
                        "PUT", profilePath + "/auth-configs/" + CHILD_ID, authConfig, SupplierPermissions.PROFILE_WRITE),
                Arguments.of("DELETE", profilePath + "/auth-configs/" + CHILD_ID, null, SupplierPermissions.PROFILE_WRITE),
                Arguments.of("GET", profilePath + "/accounts", null, SupplierPermissions.PROFILE_READ),
                Arguments.of("POST", profilePath + "/accounts", account, SupplierPermissions.PROFILE_WRITE),
                Arguments.of("PUT", profilePath + "/accounts/" + CHILD_ID, account, SupplierPermissions.PROFILE_WRITE),
                Arguments.of("DELETE", profilePath + "/accounts/" + CHILD_ID, null, SupplierPermissions.PROFILE_WRITE),
                Arguments.of("GET", profilePath + "/bindings", null, SupplierPermissions.PROFILE_READ),
                Arguments.of("POST", profilePath + "/bindings", binding, SupplierPermissions.PROFILE_WRITE),
                Arguments.of("PUT", profilePath + "/bindings/" + CHILD_ID, binding, SupplierPermissions.PROFILE_WRITE),
                Arguments.of("DELETE", profilePath + "/bindings/" + CHILD_ID, null, SupplierPermissions.PROFILE_WRITE));
    }

    private static MockHttpServletRequestBuilder request(String method, String path, String body) {
        MockHttpServletRequestBuilder builder =
                switch (method) {
                    case "GET" -> get(path);
                    case "POST" -> post(path);
                    case "PUT" -> put(path);
                    case "DELETE" -> delete(path);
                    default -> throw new IllegalArgumentException(method);
                };
        if (body != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return builder;
    }

    @ParameterizedTest(name = "{0} {1} requires {3}")
    @MethodSource("permissionTable")
    void missingAuthorityIsRejectedWith403ForbiddenEnvelope(String method, String path, String body, String permission)
            throws Exception {
        mockMvc.perform(authed(request(method, path, body), "supplier:unrelated:permission"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("You do not have permission to perform this action"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @ParameterizedTest(name = "{0} {1} passes with {3}")
    @MethodSource("permissionTable")
    void grantedAuthorityIsAccepted(String method, String path, String body, String permission) throws Exception {
        // Valid bodies + mocked service returning null: a 2xx status proves @PreAuthorize
        // accepted the granted authority and the request reached the controller.
        mockMvc.perform(authed(request(method, path, body), permission))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("%s %s with %s must reach the controller", method, path, permission)
                        .isBetween(200, 299));
    }

    @ParameterizedTest(name = "{0} {1} unauthenticated is 401")
    @MethodSource("permissionTable")
    void unauthenticatedRequestsAreRejectedWith401(String method, String path, String body, String permission)
            throws Exception {
        mockMvc.perform(request(method, path, body)).andExpect(status().isUnauthorized());
    }
}
