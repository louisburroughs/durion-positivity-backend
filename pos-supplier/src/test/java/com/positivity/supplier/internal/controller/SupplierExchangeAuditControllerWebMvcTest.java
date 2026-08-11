package com.positivity.supplier.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.supplier.internal.config.SecurityConfig;
import com.positivity.supplier.internal.exception.PayloadUnreadableException;
import com.positivity.supplier.internal.exception.SupplierExceptionHandler;
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.service.SupplierExchangeAuditService;
import com.positivity.supplier.service.model.AuditAccessKind;
import com.positivity.supplier.service.model.AuditPayloadOutcome;
import com.positivity.supplier.service.model.ExchangeAuditAccessView;
import com.positivity.supplier.service.model.ExchangeAuditPayloadView;
import com.positivity.supplier.service.model.ExchangeAuditSummary;
import com.positivity.supplier.service.model.PagedResponse;
import com.positivity.supplier.service.model.PayloadCaptureLevel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Web contract of the exchange-audit read API (ADR-0050 §7), over the real production
 * {@link SecurityConfig} chain.
 *
 * <p>The assertion this class exists for is the one ADR-0025 §4 parity debt turns on:
 * {@code supplier:audit:read} is a <strong>separate</strong> authority, and a profile administrator does
 * not get commercial payloads for free. Every endpoint is therefore exercised three ways — granted,
 * denied-with-profile-admin, and unauthenticated — rather than only with the authority that works.
 */
@WebMvcTest(controllers = SupplierExchangeAuditController.class)
@Import({SecurityConfig.class, SupplierExchangeAuditControllerWebMvcTest.FixedClockConfig.class})
class SupplierExchangeAuditControllerWebMvcTest {

    /** Same shape as the admin controllers' test: the advice needs a Clock, and the gateway filter must
     * run inside the security chain rather than ahead of it. */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC);
        }

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

    private static final String BASE = "/v1/supplier/admin/audit";
    private static final UUID EXCHANGE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a70");
    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final String CORRELATION_ID = "018f0a1b2c3d7e4f8a9b0c1d2e3f4a71";
    private static final String WINDOW =
            "?vendorProfileId=" + PROFILE_ID + "&from=2026-08-01T00:00:00Z" + "&to=2026-09-01T00:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupplierExchangeAuditService auditService;

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String... authorities) {
        return builder.header("X-User", "web-mvc-tester").header("X-Authorities", String.join(",", authorities));
    }

    private static ExchangeAuditSummary summary() {
        return new ExchangeAuditSummary(
                EXCHANGE_ID,
                PROFILE_ID,
                "michelin-eu",
                UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a60"),
                "STOCK_INQUIRY",
                "EDIWHEEL_A25",
                "A2_5",
                "POST",
                "https://edi.michelin.example/a25/stock/inquiry",
                2,
                CORRELATION_ID,
                "OK",
                200,
                Instant.parse("2026-08-11T09:14:02.117Z"),
                184L,
                null,
                PayloadCaptureLevel.REDACTED,
                true,
                true,
                null,
                "a.operator@example.com");
    }

    // ── Permission matrix ───────────────────────────────────────────────────────────

    static Stream<Arguments> auditEndpoints() {
        return Stream.of(
                Arguments.of("list exchanges", BASE + "/exchanges" + WINDOW),
                Arguments.of("trace correlation", BASE + "/exchanges/by-correlation/" + CORRELATION_ID),
                Arguments.of("get exchange", BASE + "/exchanges/" + EXCHANGE_ID),
                Arguments.of("read payload", BASE + "/exchanges/" + EXCHANGE_ID + "/payload"),
                Arguments.of("list accesses", BASE + "/exchanges/" + EXCHANGE_ID + "/accesses"));
    }

    @ParameterizedTest(name = "{0} is reachable with supplier:audit:read")
    @MethodSource("auditEndpoints")
    void grantedAuditReadReachesTheController(String name, String path) throws Exception {
        mockMvc.perform(authed(get(path), SupplierPermissions.AUDIT_READ))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("%s must reach the controller with supplier:audit:read", name)
                        .isBetween(200, 299));
    }

    @ParameterizedTest(name = "{0} is 403 without supplier:audit:read")
    @MethodSource("auditEndpoints")
    void missingAuditReadIsForbidden(String name, String path) throws Exception {
        mockMvc.perform(authed(get(path), "supplier:unrelated:permission"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @ParameterizedTest(name = "{0} is 401 unauthenticated")
    @MethodSource("auditEndpoints")
    void unauthenticatedIsRejected(String name, String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Nested
    @DisplayName("profile administration does not grant audit access (ADR-0025 §4)")
    class PermissionSeparation {

        @ParameterizedTest(name = "{0} is 403 for a profile administrator")
        @MethodSource(
                "com.positivity.supplier.internal.controller.SupplierExchangeAuditControllerWebMvcTest#auditEndpoints")
        void profileAdminCannotReadTheAuditTrail(String name, String path) throws Exception {
            // ADR-0050 §7 requires payload access to be tighter than profile admin: configuring how this
            // deployment talks to a supplier is not authority to read the commercial documents that flowed
            // over it. Both profile authorities are granted here, so this fails if either ever implies audit.
            mockMvc.perform(authed(get(path), SupplierPermissions.PROFILE_READ, SupplierPermissions.PROFILE_WRITE))
                    .andExpect(status().isForbidden());
        }

        @Test
        void aDeniedReadIsNotRecordedAsAPayloadRead() throws Exception {
            mockMvc.perform(authed(
                            get(BASE + "/exchanges/" + EXCHANGE_ID + "/payload"), SupplierPermissions.PROFILE_READ))
                    .andExpect(status().isForbidden());

            verify(auditService, never()).readPayload(any());
        }
    }

    // ── Contract round trips ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("JSON contract")
    class JsonContract {

        @Test
        void serialisesAPageOfSummariesWithAStableEnvelope() throws Exception {
            when(auditService.listExchanges(eq(PROFILE_ID), any(), any(), eq(null), anyInt(), anyInt()))
                    .thenReturn(new PagedResponse<>(List.of(summary()), 0, 50, 1L, 1));

            mockMvc.perform(authed(get(BASE + "/exchanges" + WINDOW), SupplierPermissions.AUDIT_READ))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].exchangeAuditId").value(EXCHANGE_ID.toString()))
                    .andExpect(jsonPath("$.items[0].supplierRef").value("michelin-eu"))
                    .andExpect(jsonPath("$.items[0].capability").value("STOCK_INQUIRY"))
                    .andExpect(jsonPath("$.items[0].requestPayloadPresent").value(true))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    // The stable envelope exists so Spring Data's Page shape never reaches the SDK.
                    .andExpect(jsonPath("$.pageable").doesNotExist())
                    .andExpect(jsonPath("$.content").doesNotExist());
        }

        @Test
        void aSummaryNeverCarriesPayloadContent() throws Exception {
            when(auditService.getExchange(EXCHANGE_ID)).thenReturn(summary());

            String body = mockMvc.perform(
                            authed(get(BASE + "/exchanges/" + EXCHANGE_ID), SupplierPermissions.AUDIT_READ))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(body)
                    .as("the metadata shape must not grow payload fields: that would turn every listing into an"
                            + " unrecorded content disclosure")
                    .doesNotContain("requestPayload\"")
                    .doesNotContain("responsePayload\"")
                    .contains("requestPayloadPresent");
        }

        @Test
        void passesTheCapabilityFilterThrough() throws Exception {
            mockMvc.perform(authed(
                            get(BASE + "/exchanges" + WINDOW + "&capability=STOCK_INQUIRY"),
                            SupplierPermissions.AUDIT_READ))
                    .andExpect(status().isOk());

            verify(auditService)
                    .listExchanges(
                            eq(PROFILE_ID),
                            eq(Instant.parse("2026-08-01T00:00:00Z")),
                            eq(Instant.parse("2026-09-01T00:00:00Z")),
                            eq("STOCK_INQUIRY"),
                            eq(0),
                            eq(50));
        }

        @Test
        void serialisesThePayloadViewIncludingItsRedactionWarning() throws Exception {
            when(auditService.readPayload(EXCHANGE_ID))
                    .thenReturn(new ExchangeAuditPayloadView(
                            EXCHANGE_ID,
                            PayloadCaptureLevel.REDACTED,
                            true,
                            "<StockInquiry><Password>***REDACTED***</Password></StockInquiry>",
                            "<StockInquiryResponse/>"));

            mockMvc.perform(authed(
                            get(BASE + "/exchanges/" + EXCHANGE_ID + "/payload"), SupplierPermissions.AUDIT_READ))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.redacted").value(true))
                    .andExpect(jsonPath("$.captureLevel").value("REDACTED"))
                    .andExpect(jsonPath("$.requestPayload").value(containsString("***REDACTED***")));
        }

        @Test
        void servesNullContentAsAnEmptyViewRatherThanAnError() throws Exception {
            when(auditService.readPayload(EXCHANGE_ID))
                    .thenReturn(new ExchangeAuditPayloadView(
                            EXCHANGE_ID, PayloadCaptureLevel.METADATA_ONLY, false, null, null));

            mockMvc.perform(authed(
                            get(BASE + "/exchanges/" + EXCHANGE_ID + "/payload"), SupplierPermissions.AUDIT_READ))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.captureLevel").value("METADATA_ONLY"));
        }

        @Test
        void serialisesTheAccessTrail() throws Exception {
            when(auditService.listAccesses(eq(EXCHANGE_ID), anyInt(), anyInt()))
                    .thenReturn(new PagedResponse<>(
                            List.of(new ExchangeAuditAccessView(
                                    UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a80"),
                                    EXCHANGE_ID,
                                    PROFILE_ID,
                                    "STOCK_INQUIRY",
                                    "a.operator@example.com",
                                    Instant.parse("2026-08-11T14:22:07.412Z"),
                                    AuditAccessKind.PAYLOAD_READ,
                                    AuditPayloadOutcome.UNREADABLE)),
                            0,
                            50,
                            1L,
                            1));

            mockMvc.perform(authed(
                            get(BASE + "/exchanges/" + EXCHANGE_ID + "/accesses"), SupplierPermissions.AUDIT_READ))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].accessedBy").value("a.operator@example.com"))
                    .andExpect(jsonPath("$.items[0].accessKind").value("PAYLOAD_READ"))
                    .andExpect(jsonPath("$.items[0].payloadOutcome").value("UNREADABLE"));
        }
    }

    // ── Error translation ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("error translation")
    class Errors {

        @Test
        void unknownExchangeIsA404WithItsDomainCode() throws Exception {
            when(auditService.getExchange(EXCHANGE_ID))
                    .thenThrow(new SupplierNotFoundException(
                            SupplierNotFoundException.EXCHANGE_AUDIT_NOT_FOUND, "No supplier exchange-audit record"));

            mockMvc.perform(authed(get(BASE + "/exchanges/" + EXCHANGE_ID), SupplierPermissions.AUDIT_READ))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("SUPPLIER_EXCHANGE_AUDIT_NOT_FOUND"));
        }

        @ParameterizedTest(name = "unreadable content [{0}] is a typed 500 that leaks nothing")
        @ValueSource(
                strings = {
                    PayloadUnreadableException.UNKNOWN_KEY_ID,
                    PayloadUnreadableException.AUTHENTICATION_FAILED,
                    PayloadUnreadableException.MALFORMED_ENVELOPE
                })
        void unreadablePayloadIsTypedAndSaysNothingAboutTheEncryptionScheme(String code) throws Exception {
            when(auditService.readPayload(EXCHANGE_ID))
                    .thenThrow(new PayloadUnreadableException(
                            code,
                            "Exchange-audit payload was sealed with key id 'retired-key-2025', which is not"
                                    + " configured. Add it to pos.supplier.audit.encryption.previous-keys to read"
                                    + " pre-rotation rows."));

            mockMvc.perform(authed(
                            get(BASE + "/exchanges/" + EXCHANGE_ID + "/payload"), SupplierPermissions.AUDIT_READ))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value(code))
                    .andExpect(jsonPath("$.message").value(SupplierExceptionHandler.PAYLOAD_UNREADABLE_MESSAGE))
                    // The exception's own message is useful in a log and is nobody's business over HTTP.
                    .andExpect(jsonPath("$.message").value(not(containsString("retired-key-2025"))))
                    .andExpect(jsonPath("$.message").value(not(containsString("pos.supplier.audit"))))
                    .andExpect(jsonPath("$.message").value(not(containsString("previous-keys"))));
        }

        @Test
        void anOversizedPageIsRejected() throws Exception {
            mockMvc.perform(authed(get(BASE + "/exchanges" + WINDOW + "&size=201"), SupplierPermissions.AUDIT_READ))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void aMissingWindowIsRejected() throws Exception {
            mockMvc.perform(authed(
                            get(BASE + "/exchanges?vendorProfileId=" + PROFILE_ID), SupplierPermissions.AUDIT_READ))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
        }
    }
}
