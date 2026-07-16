package com.positivity.warranty.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.warranty.internal.config.SecurityConfig;
import com.positivity.warranty.internal.dto.ClaimCreateRequest;
import com.positivity.warranty.internal.dto.ClaimResponse;
import com.positivity.warranty.internal.dto.PartReturnResponse;
import com.positivity.warranty.internal.dto.PolicyResponse;
import com.positivity.warranty.internal.dto.ProviderRequest;
import com.positivity.warranty.internal.dto.ProviderResponse;
import com.positivity.warranty.internal.dto.RegistrationResponse;
import com.positivity.warranty.internal.dto.ReimbursementResponse;
import com.positivity.warranty.internal.dto.SettlementResponse;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.enums.ClaimType;
import com.positivity.warranty.internal.enums.LineSourceType;
import com.positivity.warranty.internal.enums.PartReturnDisposition;
import com.positivity.warranty.internal.enums.PartReturnStatus;
import com.positivity.warranty.internal.enums.ProviderType;
import com.positivity.warranty.internal.enums.ReimbursementStatus;
import com.positivity.warranty.internal.enums.SettlementStatus;
import com.positivity.warranty.internal.enums.SettlementType;
import com.positivity.warranty.internal.exception.IllegalClaimStateException;
import com.positivity.warranty.internal.exception.WarrantyIntegrationException;
import com.positivity.warranty.internal.exception.WarrantyNotFoundException;
import com.positivity.warranty.internal.exception.WarrantyUnprocessableException;
import com.positivity.warranty.internal.security.WarrantyPermissions;
import com.positivity.warranty.internal.service.CandidateLineService;
import com.positivity.warranty.internal.service.ClaimService;
import com.positivity.warranty.internal.service.PartReturnService;
import com.positivity.warranty.internal.service.PolicyService;
import com.positivity.warranty.internal.service.ProviderService;
import com.positivity.warranty.internal.service.RegistrationService;
import com.positivity.warranty.internal.service.ReimbursementService;
import com.positivity.warranty.internal.service.SettlementService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * Web-layer contract of the seven pos-warranty controllers over real HTTP semantics (PRD §5,
 * §8): Jackson round-trips of the record request DTOs, jakarta validation → 400 {@code
 * ApiError.fieldErrors}, {@code WarrantyExceptionHandler} mapping (409 + {@code nextAction} on
 * illegal transitions, 404/422/502, correlation-id echo and generated fallback), and — via the
 * imported production {@link SecurityConfig}/{@code GatewaySecurityConfig} chain — the §8
 * permission table: every {@code WarrantyPermissions} constant is exercised with 403 (missing
 * authority, {@code FORBIDDEN} envelope), 2xx (granted), and 401 (unauthenticated).
 */
@WebMvcTest(
        controllers = {
            ClaimController.class,
            PolicyController.class,
            ProviderController.class,
            RegistrationController.class,
            ReimbursementController.class,
            PartReturnController.class,
            SettlementController.class
        })
@Import({SecurityConfig.class, WarrantyControllersWebMvcTest.FixedClockConfig.class})
class WarrantyControllersWebMvcTest {

    /** TimeConfig-style Clock is not auto-configured in the slice; the advice needs one. */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC);
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

    private static final UUID CLAIM_ID = UUID.fromString("018f0000-0000-7000-8000-000000000401");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000402");
    private static final UUID VEHICLE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000403");
    private static final UUID GENERIC_ID = UUID.fromString("018f0000-0000-7000-8000-000000000404");
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ClaimService claimService;

    @MockitoBean
    private CandidateLineService candidateLineService;

    @MockitoBean
    private PolicyService policyService;

    @MockitoBean
    private ProviderService providerService;

    @MockitoBean
    private RegistrationService registrationService;

    @MockitoBean
    private ReimbursementService reimbursementService;

    @MockitoBean
    private PartReturnService partReturnService;

    @MockitoBean
    private SettlementService settlementService;

    // ------------------------------------------------------------------ helpers

    /** Authenticates the request the way the API gateway does: X-Authorities / X-User headers. */
    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String... authorities) {
        return builder.header("X-User", "web-mvc-tester").header("X-Authorities", String.join(",", authorities));
    }

    private static ClaimResponse claimResponse(ClaimStatus status) {
        return new ClaimResponse(
                CLAIM_ID,
                "WC-2026-000042",
                ClaimType.MANUFACTURER_DEFECT,
                status,
                null,
                CUSTOMER_ID,
                VEHICLE_ID,
                "1HGCM82633A004352",
                42_000L,
                "MILES",
                null,
                null,
                LocalDate.of(2025, 6, 1),
                false,
                null,
                null,
                null,
                "sidewall separation",
                LocalDate.of(2026, 7, 1),
                List.of("https://p/1.jpg"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Instant.parse("2026-07-15T12:00:00Z"),
                "web-mvc-tester",
                Instant.parse("2026-07-15T12:00:00Z"),
                0L);
    }

    private static String claimCreateJson() {
        return """
                {
                  "claimType": "MANUFACTURER_DEFECT",
                  "customerId": "%s",
                  "vehicleId": "%s",
                  "originSaleDate": "2025-06-01",
                  "failureDescription": "sidewall separation",
                  "failureDate": "2026-07-01",
                  "photoEvidenceUrls": ["https://p/1.jpg"],
                  "lines": [{
                    "sourceType": "MANUAL",
                    "sku": "TIRE-205-55R16",
                    "description": "All-season tire",
                    "quantity": 1,
                    "originalUnitPrice": 129.99,
                    "originalTreadDepth": 12,
                    "measuredTreadDepth": 6
                  }]
                }
                """.formatted(CUSTOMER_ID, VEHICLE_ID);
    }

    // ------------------------------------------------------------------ happy-path round trips

    @Nested
    class HappyPathJsonRoundTrips {

        @Test
        void createClaimDeserializesTheRecordGraphAndSerializesTheResponse() throws Exception {
            when(claimService.create(any())).thenReturn(claimResponse(ClaimStatus.DRAFT));

            mockMvc.perform(authed(
                            post("/v1/warranty/claims")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(claimCreateJson()),
                            WarrantyPermissions.CLAIM_CREATE))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(CLAIM_ID.toString()))
                    .andExpect(jsonPath("$.claimCode").value("WC-2026-000042"))
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.vin").value("1HGCM82633A004352"))
                    .andExpect(jsonPath("$.odometerAtClaim").value(42_000))
                    .andExpect(jsonPath("$.photoEvidenceUrls[0]").value("https://p/1.jpg"));

            ArgumentCaptor<ClaimCreateRequest> captor = ArgumentCaptor.captor();
            verify(claimService).create(captor.capture());
            ClaimCreateRequest deserialized = captor.getValue();
            assertThat(deserialized.claimType()).isEqualTo(ClaimType.MANUFACTURER_DEFECT);
            assertThat(deserialized.customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(deserialized.vehicleId()).isEqualTo(VEHICLE_ID);
            assertThat(deserialized.originSaleDate()).isEqualTo(LocalDate.of(2025, 6, 1));
            assertThat(deserialized.lines()).hasSize(1);
            assertThat(deserialized.lines().getFirst().sourceType()).isEqualTo(LineSourceType.MANUAL);
            assertThat(deserialized.lines().getFirst().sku()).isEqualTo("TIRE-205-55R16");
            assertThat(deserialized.lines().getFirst().originalUnitPrice()).isEqualByComparingTo("129.99");
            assertThat(deserialized.lines().getFirst().measuredTreadDepth()).isEqualTo(6);
        }

        @Test
        void createProviderRoundTripsTheProviderRecord() throws Exception {
            when(providerService.create(any()))
                    .thenReturn(new ProviderResponse(
                            GENERIC_ID,
                            "Michelin North America",
                            "ACTIVE",
                            ProviderType.MANUFACTURER,
                            null,
                            null,
                            "Portal",
                            null,
                            null,
                            null,
                            null,
                            Instant.parse("2026-07-15T12:00:00Z"),
                            null,
                            0L));

            mockMvc.perform(authed(
                            post("/v1/warranty/providers")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"name": "Michelin North America", "providerType": "MANUFACTURER",
                                             "claimSubmissionMethod": "Portal"}
                                            """),
                            WarrantyPermissions.PROVIDER_MANAGE))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(GENERIC_ID.toString()))
                    .andExpect(jsonPath("$.name").value("Michelin North America"))
                    .andExpect(jsonPath("$.providerType").value("MANUFACTURER"));

            ArgumentCaptor<ProviderRequest> captor = ArgumentCaptor.captor();
            verify(providerService).create(captor.capture());
            assertThat(captor.getValue().name()).isEqualTo("Michelin North America");
            assertThat(captor.getValue().providerType()).isEqualTo(ProviderType.MANUFACTURER);
        }

        @Test
        void policyListSerializesThePolicyRecord() throws Exception {
            when(policyService.list(eq(null), eq(null)))
                    .thenReturn(List.of(new PolicyResponse(
                            GENERIC_ID,
                            GENERIC_ID,
                            "Road Hazard Gold",
                            null,
                            null,
                            null,
                            null,
                            null,
                            LocalDate.of(2026, 1, 1),
                            null,
                            36,
                            null,
                            2,
                            true,
                            null,
                            null,
                            null,
                            true,
                            true,
                            false,
                            null,
                            null,
                            null,
                            null,
                            0L)));

            mockMvc.perform(authed(get("/v1/warranty/policies"), WarrantyPermissions.POLICY_VIEW))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].name").value("Road Hazard Gold"))
                    .andExpect(jsonPath("$[0].durationMonths").value(36))
                    .andExpect(jsonPath("$[0].requiresPartReturn").value(true));
        }

        @Test
        void registrationSearchSerializesTheRegistrationRecord() throws Exception {
            when(registrationService.search(eq(CUSTOMER_ID), eq(null), eq(null)))
                    .thenReturn(List.of(new RegistrationResponse(
                            GENERIC_ID,
                            GENERIC_ID,
                            CUSTOMER_ID,
                            VEHICLE_ID,
                            null,
                            null,
                            "RH-2026-88412",
                            LocalDate.of(2026, 1, 15),
                            null,
                            null,
                            null,
                            null,
                            0L)));

            mockMvc.perform(authed(
                            get("/v1/warranty/registrations").param("customerId", CUSTOMER_ID.toString()),
                            WarrantyPermissions.REGISTRATION_VIEW))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].contractNumber").value("RH-2026-88412"))
                    .andExpect(jsonPath("$[0].purchaseDate").value("2026-01-15"));
        }

        @Test
        void reimbursementSubmitRoundTrips() throws Exception {
            when(reimbursementService.submit(eq(CLAIM_ID), any()))
                    .thenReturn(new ReimbursementResponse(
                            GENERIC_ID,
                            CLAIM_ID,
                            GENERIC_ID,
                            ReimbursementStatus.SUBMITTED,
                            new BigDecimal("84.21"),
                            null,
                            "VENDOR-REF-9",
                            Instant.parse("2026-07-15T12:00:00Z"),
                            "web-mvc-tester",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null));

            mockMvc.perform(authed(
                            post("/v1/warranty/claims/{id}/reimbursement/submit", CLAIM_ID)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"amountRequested\": 84.21, \"vendorClaimReference\": \"VENDOR-REF-9\"}"),
                            WarrantyPermissions.REIMBURSEMENT_MANAGE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUBMITTED"))
                    .andExpect(jsonPath("$.amountRequested").value(84.21))
                    .andExpect(jsonPath("$.vendorClaimReference").value("VENDOR-REF-9"));
        }

        @Test
        void partReturnCreateRoundTrips() throws Exception {
            when(partReturnService.create(eq(CLAIM_ID), any()))
                    .thenReturn(new PartReturnResponse(
                            GENERIC_ID,
                            CLAIM_ID,
                            GENERIC_ID,
                            "RMA-778",
                            PartReturnDisposition.RETURN_TO_VENDOR,
                            PartReturnStatus.AWAITING_PART,
                            null,
                            null,
                            null,
                            "shelf B-4",
                            null,
                            null));

            mockMvc.perform(authed(
                            post("/v1/warranty/claims/{id}/part-returns", CLAIM_ID)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"claimLineId\": \"%s\", \"disposition\": \"RETURN_TO_VENDOR\", \"rmaNumber\": \"RMA-778\"}"
                                                    .formatted(GENERIC_ID)),
                            WarrantyPermissions.PART_RETURN_MANAGE))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.rmaNumber").value("RMA-778"))
                    .andExpect(jsonPath("$.status").value("AWAITING_PART"))
                    .andExpect(jsonPath("$.disposition").value("RETURN_TO_VENDOR"));
        }

        @Test
        void settlementCreateRoundTrips() throws Exception {
            when(settlementService.create(eq(CLAIM_ID), any()))
                    .thenReturn(new SettlementResponse(
                            GENERIC_ID,
                            CLAIM_ID,
                            SettlementType.INVOICE_CREDIT,
                            SettlementStatus.COMPLETED,
                            null,
                            GENERIC_ID,
                            null,
                            null,
                            new BigDecimal("84.21"),
                            new BigDecimal("15.00"),
                            ClaimStatus.SETTLED,
                            null,
                            null));

            mockMvc.perform(authed(
                            post("/v1/warranty/claims/{id}/settlements", CLAIM_ID)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"settlementType": "INVOICE_CREDIT", "coveredAmount": 84.21,
                                             "customerAmount": 15.00, "invoiceId": "%s"}
                                            """.formatted(GENERIC_ID)),
                            WarrantyPermissions.CLAIM_SETTLE))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.settlementType").value("INVOICE_CREDIT"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.claimStatus").value("SETTLED"));
        }
    }

    // ------------------------------------------------------------------ ApiError envelope (PRD §5, §8)

    @Nested
    class ErrorEnvelope {

        @Test
        void illegalTransitionIs409WithNextActionAndEchoedCorrelationId() throws Exception {
            when(claimService.submit(CLAIM_ID))
                    .thenThrow(new IllegalClaimStateException(
                            "WARRANTY_CLAIM_TRANSITION",
                            "Claim WC-2026-000042 cannot move from APPROVED to SUBMITTED",
                            "Legal next statuses: SETTLED, CLOSED."));

            mockMvc.perform(authed(post("/v1/warranty/claims/{id}/submit", CLAIM_ID), WarrantyPermissions.CLAIM_SUBMIT)
                            .header(CORRELATION_HEADER, "corr-42"))
                    .andExpect(status().isConflict())
                    .andExpect(header().string(CORRELATION_HEADER, "corr-42"))
                    .andExpect(jsonPath("$.code").value("WARRANTY_CLAIM_TRANSITION"))
                    .andExpect(
                            jsonPath("$.message").value("Claim WC-2026-000042 cannot move from APPROVED to SUBMITTED"))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.timestamp").value("2026-07-15T12:00:00Z"))
                    .andExpect(jsonPath("$.correlationId").value("corr-42"))
                    .andExpect(jsonPath("$.nextAction").value("Legal next statuses: SETTLED, CLOSED."));
        }

        @Test
        void missingCorrelationHeaderGetsAGeneratedIdEchoedInHeaderAndBody() throws Exception {
            when(claimService.submit(CLAIM_ID))
                    .thenThrow(new IllegalClaimStateException("WARRANTY_CLAIM_TRANSITION", "illegal", "next"));

            var result = mockMvc.perform(
                            authed(post("/v1/warranty/claims/{id}/submit", CLAIM_ID), WarrantyPermissions.CLAIM_SUBMIT))
                    .andExpect(status().isConflict())
                    .andReturn();

            String headerValue = result.getResponse().getHeader(CORRELATION_HEADER);
            assertThat(headerValue).isNotBlank();
            assertThat(result.getResponse().getContentAsString()).contains("\"correlationId\":\"" + headerValue + "\"");
        }

        @Test
        void beanValidationFailureIs400WithFieldErrors() throws Exception {
            mockMvc.perform(authed(
                            post("/v1/warranty/claims")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    // claimType, customerId, and vehicleId are @NotNull
                                    .content("{\"failureDescription\": \"broke\"}"),
                            WarrantyPermissions.CLAIM_CREATE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.message").value("Request validation failed"))
                    .andExpect(jsonPath("$.fieldErrors[*].field")
                            .value(org.hamcrest.Matchers.containsInAnyOrder("claimType", "customerId", "vehicleId")));
        }

        @Test
        void malformedJsonIs400MalformedRequest() throws Exception {
            mockMvc.perform(authed(
                            post("/v1/warranty/claims")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{not json"),
                            WarrantyPermissions.CLAIM_CREATE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }

        @Test
        void unknownClaimIs404WithTheDomainCode() throws Exception {
            when(claimService.getById(CLAIM_ID))
                    .thenThrow(new WarrantyNotFoundException("WARRANTY_CLAIM_NOT_FOUND", "Warranty claim not found"));

            mockMvc.perform(authed(get("/v1/warranty/claims/{id}", CLAIM_ID), WarrantyPermissions.CLAIM_VIEW))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("WARRANTY_CLAIM_NOT_FOUND"));
        }

        @Test
        void unresolvableCrossServiceReferenceIs422() throws Exception {
            when(settlementService.create(eq(CLAIM_ID), any()))
                    .thenThrow(new WarrantyUnprocessableException(
                            "WARRANTY_SETTLEMENT_WORKORDER_NOT_FOUND", "Replacement workorder could not be resolved"));

            mockMvc.perform(authed(
                            post("/v1/warranty/claims/{id}/settlements", CLAIM_ID)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"settlementType": "REPLACEMENT_WORKORDER", "coveredAmount": 0,
                                             "customerAmount": 0, "replacementWorkorderId": "%s"}
                                            """.formatted(GENERIC_ID)),
                            WarrantyPermissions.CLAIM_SETTLE))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("WARRANTY_SETTLEMENT_WORKORDER_NOT_FOUND"));
        }

        @Test
        void integrationOutageIs502SoCallersCanDistinguishItFromAWarrantyBug() throws Exception {
            when(settlementService.create(eq(CLAIM_ID), any()))
                    .thenThrow(new WarrantyIntegrationException("pos-invoice returned 503"));

            mockMvc.perform(authed(
                            post("/v1/warranty/claims/{id}/settlements", CLAIM_ID)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"settlementType": "INVOICE_CREDIT", "coveredAmount": 10,
                                             "customerAmount": 0, "invoiceId": "%s"}
                                            """.formatted(GENERIC_ID)),
                            WarrantyPermissions.CLAIM_SETTLE))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("WARRANTY_INTEGRATION_ERROR"));
        }
    }

    // ------------------------------------------------------------------ §8 permission table

    /** One representative endpoint per permission constant in {@code WarrantyPermissions}. */
    static Stream<Arguments> permissionTable() {
        String provider = "{\"name\": \"Michelin\", \"providerType\": \"MANUFACTURER\"}";
        String policy = """
                {"providerId": "%s", "name": "Road Hazard Gold", "coverageType": "ROAD_HAZARD",
                 "appliesToType": "CATEGORY", "appliesToCategoryId": "%s",
                 "effectiveFrom": "2026-01-01", "prorationMethod": "NONE"}
                """.formatted(GENERIC_ID, GENERIC_ID);
        String registration = "{\"policyId\": \"%s\", \"customerId\": \"%s\", \"purchaseDate\": \"2026-01-15\"}"
                .formatted(GENERIC_ID, CUSTOMER_ID);
        String decision = "{\"decision\": \"APPROVE\"}";
        String settlement = "{\"settlementType\": \"NO_ACTION\", \"coveredAmount\": 0, \"customerAmount\": 0}";
        String reimbursement = "{\"amountRequested\": 10}";
        String partReturn = "{\"claimLineId\": \"%s\", \"disposition\": \"RETURN_TO_VENDOR\"}".formatted(GENERIC_ID);
        // Bodies must be VALID: body deserialization/validation runs before @PreAuthorize, so an
        // invalid body would 400 and mask the security assertion.
        String claimCreate = "{\"claimType\": \"MANUFACTURER_DEFECT\", \"customerId\": \"%s\", \"vehicleId\": \"%s\"}"
                .formatted(CUSTOMER_ID, VEHICLE_ID);
        String claims = "/v1/warranty/claims/" + CLAIM_ID;
        return Stream.of(
                Arguments.of("GET", "/v1/warranty/providers", null, WarrantyPermissions.PROVIDER_VIEW),
                Arguments.of("POST", "/v1/warranty/providers", provider, WarrantyPermissions.PROVIDER_MANAGE),
                Arguments.of(
                        "PUT", "/v1/warranty/providers/" + GENERIC_ID, provider, WarrantyPermissions.PROVIDER_MANAGE),
                Arguments.of("GET", "/v1/warranty/policies", null, WarrantyPermissions.POLICY_VIEW),
                Arguments.of("POST", "/v1/warranty/policies", policy, WarrantyPermissions.POLICY_MANAGE),
                Arguments.of("GET", "/v1/warranty/registrations", null, WarrantyPermissions.REGISTRATION_VIEW),
                Arguments.of(
                        "POST", "/v1/warranty/registrations", registration, WarrantyPermissions.REGISTRATION_MANAGE),
                Arguments.of("GET", "/v1/warranty/claims", null, WarrantyPermissions.CLAIM_VIEW),
                Arguments.of("POST", "/v1/warranty/claims", claimCreate, WarrantyPermissions.CLAIM_CREATE),
                Arguments.of("POST", claims + "/submit", null, WarrantyPermissions.CLAIM_SUBMIT),
                Arguments.of("POST", claims + "/decision", decision, WarrantyPermissions.CLAIM_DECIDE),
                Arguments.of("POST", claims + "/settlements", settlement, WarrantyPermissions.CLAIM_SETTLE),
                Arguments.of("POST", claims + "/cancel", null, WarrantyPermissions.CLAIM_CANCEL),
                Arguments.of("POST", claims + "/close", null, WarrantyPermissions.CLAIM_CLOSE),
                Arguments.of(
                        "POST",
                        claims + "/reimbursement/submit",
                        reimbursement,
                        WarrantyPermissions.REIMBURSEMENT_MANAGE),
                Arguments.of("GET", "/v1/warranty/reimbursements", null, WarrantyPermissions.REIMBURSEMENT_VIEW),
                Arguments.of("POST", claims + "/part-returns", partReturn, WarrantyPermissions.PART_RETURN_MANAGE),
                Arguments.of("GET", "/v1/warranty/part-returns", null, WarrantyPermissions.PART_RETURN_VIEW));
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
    void missingAuthorityIsRejectedWith403ForbiddenEnvelope(String method, String path, String body, String permission)
            throws Exception {
        mockMvc.perform(authed(request(method, path, body), "warranty:unrelated:permission"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("You do not have permission to perform this action"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @ParameterizedTest(name = "{0} {1} passes with {3}")
    @MethodSource("permissionTable")
    void grantedAuthorityIsAccepted(String method, String path, String body, String permission) throws Exception {
        // Valid bodies + mocked services returning null: a 2xx status proves @PreAuthorize
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
