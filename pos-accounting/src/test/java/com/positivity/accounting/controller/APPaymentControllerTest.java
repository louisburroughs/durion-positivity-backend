package com.positivity.accounting.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import com.positivity.accounting.internal.dto.APPaymentResponse;
import com.positivity.accounting.service.APPaymentService;

/**
 * Unit tests for APPaymentController input validation.
 * 
 * Focuses on security validation (S5145) to prevent log injection attacks
 * via untrusted path variables like paymentRef.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("APPaymentController Input Validation Tests")
class APPaymentControllerTest {

        @Autowired
        private WebApplicationContext context;

        private MockMvc mockMvc;

        @MockitoBean
        private APPaymentService apPaymentService;

        @BeforeEach
        void setUp() {
                // Initialize MockMvc with Spring Security
                this.mockMvc = webAppContextSetup(context)
                                .apply(springSecurity())
                                .build();
        }

        @Nested
        @DisplayName("Get Payment By Ref - paymentRef Validation (S5145)")
        class GetPaymentByRefValidation {

                @Test
                @DisplayName("Should accept valid payment reference")
                void shouldAcceptValidPaymentRef() throws Exception {
                        // Given: Valid payment reference (UUID format)
                        String validRef = "01936e5c-7890-7a3d-8b6e-2b3456789012";
                        when(apPaymentService.getPaymentByRef(validRef))
                                        .thenReturn(Optional.empty());

                        // When: Request with valid reference
                        mockMvc.perform(get("/v1/accounting/ap/payments/by-ref/{paymentRef}", validRef)
                                        .header("X-Authorities", "ap:payment:view")
                                        .header("X-User", "test-user"))
                                        // Then: Should succeed (404 because no payment found, but validation passed)
                                        .andExpect(status().isNotFound());

                        verify(apPaymentService).getPaymentByRef(validRef);
                }

                @Test
                @DisplayName("Should accept alphanumeric payment reference")
                void shouldAcceptAlphanumericPaymentRef() throws Exception {
                        // Given: Valid alphanumeric reference
                        String validRef = "PAY-2024-001-ABC";
                        when(apPaymentService.getPaymentByRef(validRef))
                                        .thenReturn(Optional.empty());

                        // When: Request with alphanumeric reference
                        mockMvc.perform(get("/v1/accounting/ap/payments/by-ref/{paymentRef}", validRef)
                                        .header("X-Authorities", "ap:payment:view")
                                        .header("X-User", "test-user"))
                                        // Then: Should succeed
                                        .andExpect(status().isNotFound());

                        verify(apPaymentService).getPaymentByRef(validRef);
                }

                @Test
                @DisplayName("Should reject CRLF injection attempt with newline (security S5145) - blocked by Spring Security firewall")
                void shouldRejectNewlineInjection() throws Exception {
                        // Given: Malicious reference with newline character (URL-encoded)
                        // Note: Spring Security's StrictHttpFirewall will block this before it reaches our validation
                        String maliciousRef = "VALID_REF%0A[FAKE_LOG_ENTRY]";

                        // When: Request with CRLF injection
                        // Then: Spring Security firewall blocks the request before it reaches controller
                        // This is defense-in-depth: firewall + our validation both protect against log injection
                        mockMvc.perform(get("/v1/accounting/ap/payments/by-ref/{paymentRef}", maliciousRef)
                                        .header("X-Authorities", "ap:payment:view")
                                        .header("X-User", "test-user"))
                                        // Firewall returns 400 Bad Request for blocked URLs
                                        .andExpect(status().isBadRequest());

                        // Service should not be called when firewall blocks the request
                        verify(apPaymentService, never()).getPaymentByRef(any());
                }

                @Test
                @DisplayName("Should reject CRLF injection with carriage return (security S5145) - blocked by Spring Security firewall")
                void shouldRejectCarriageReturnInjection() throws Exception {
                        // Given: Malicious reference with carriage return (URL-encoded)
                        String maliciousRef = "VALID_REF%0D[FAKE_LOG]";

                        // When: Request with CRLF injection
                        // Then: Firewall blocks before reaching controller
                        mockMvc.perform(get("/v1/accounting/ap/payments/by-ref/{paymentRef}", maliciousRef)
                                        .header("X-Authorities", "ap:payment:view")
                                        .header("X-User", "test-user"))
                                        .andExpect(status().isBadRequest());

                        verify(apPaymentService, never()).getPaymentByRef(any());
                }

                @Test
                @DisplayName("Should reject CRLF injection with both CR and LF - blocked by Spring Security firewall")
                void shouldRejectCRLFCombination() throws Exception {
                        // Given: Malicious reference with both CR and LF
                        String maliciousRef = "VALID%0D%0AFAKE_AUDIT_ENTRY";

                        // When: Request with CRLF injection
                        // Then: Firewall blocks before reaching controller
                        mockMvc.perform(get("/v1/accounting/ap/payments/by-ref/{paymentRef}", maliciousRef)
                                        .header("X-Authorities", "ap:payment:view")
                                        .header("X-User", "test-user"))
                                        .andExpect(status().isBadRequest());

                        verify(apPaymentService, never()).getPaymentByRef(any());
                }

                @Test
                @DisplayName("Should reject payment reference exceeding 100 characters")
                void shouldRejectExcessiveLength() throws Exception {
                        // Given: Reference exceeding max length
                        String tooLongRef = "A".repeat(101);

                        // When: Request with excessive length
                        mockMvc.perform(get("/v1/accounting/ap/payments/by-ref/{paymentRef}", tooLongRef)
                                        .header("X-Authorities", "ap:payment:view")
                                        .header("X-User", "test-user"))
                                        // Then: Should return 400 Bad Request
                                        .andExpect(status().isBadRequest())
                                        .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                                        .andExpect(jsonPath("$.message", containsString("1-100 characters")));

                        verify(apPaymentService, never()).getPaymentByRef(any());
                }

                @Test
                @DisplayName("Should accept payment reference at max length (100 chars)")
                void shouldAcceptMaxLength() throws Exception {
                        // Given: Reference at max length
                        String maxLengthRef = "A".repeat(100);
                        when(apPaymentService.getPaymentByRef(maxLengthRef))
                                        .thenReturn(Optional.empty());

                        // When: Request with max length reference
                        mockMvc.perform(get("/v1/accounting/ap/payments/by-ref/{paymentRef}", maxLengthRef)
                                        .header("X-Authorities", "ap:payment:view")
                                        .header("X-User", "test-user"))
                                        // Then: Should succeed
                                        .andExpect(status().isNotFound());

                        verify(apPaymentService).getPaymentByRef(maxLengthRef);
                }

                @Test
                @DisplayName("Should accept single character payment reference")
                void shouldAcceptMinLength() throws Exception {
                        // Given: Single character reference
                        String minLengthRef = "A";
                        when(apPaymentService.getPaymentByRef(minLengthRef))
                                        .thenReturn(Optional.empty());

                        // When: Request with min length reference
                        mockMvc.perform(get("/v1/accounting/ap/payments/by-ref/{paymentRef}", minLengthRef)
                                        .header("X-Authorities", "ap:payment:view")
                                        .header("X-User", "test-user"))
                                        // Then: Should succeed
                                        .andExpect(status().isNotFound());

                        verify(apPaymentService).getPaymentByRef(minLengthRef);
                }

                @Test
                @DisplayName("Should accept common payment reference formats")
                void shouldAcceptCommonFormats() throws Exception {
                        // Test multiple common valid formats
                        String[] validRefs = {
                                        "01936e5c-7890-7a3d-8b6e-2b3456789012", // UUID
                                        "PAY-2024-001", // Structured ID
                                        "INV12345", // Simple alphanumeric
                                        "payment_12345_vendor_789", // Underscore separated
                                        "stripe-ch_1234567890abcdef" // Payment gateway format
                        };

                        for (String ref : validRefs) {
                                when(apPaymentService.getPaymentByRef(ref))
                                                .thenReturn(Optional.empty());

                                mockMvc.perform(get("/v1/accounting/ap/payments/by-ref/{paymentRef}", ref)
                                                .header("X-Authorities", "ap:payment:view")
                                                .header("X-User", "test-user"))
                                                .andExpect(status().isNotFound());

                                verify(apPaymentService).getPaymentByRef(ref);
                        }
                }
        }
}
