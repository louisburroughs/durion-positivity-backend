package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.internal.client.TaxRegistrationClient;
import com.positivity.accounting.internal.entity.ExtTaxRegistration;
import com.positivity.accounting.internal.exception.TaxRegistrationRelayException;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.accounting.internal.repository.ExtTaxRegistrationRepository;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.TaxRegistrationFrontDoorServiceImpl;
import com.positivity.shared.error.ApiError;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CAP:550 S32c AC 1, 2 and 4 at the accounting front door: the two permissions, the front door's own checks, the
 * relay of pos-tax's refusals, 503 when pos-tax cannot be reached, the actor taken only from {@code X-User-Id}, and
 * the as-of read of accounting's copy. pos-tax itself is the mocked client.
 */
@WebMvcTest(TaxRegistrationController.class)
@Import({TaxRegistrationFrontDoorServiceImpl.class, TaxRegistrationControllerTest.SliceTestConfig.class})
@DisplayName("/v1/accounting/tax-registrations — the front door to pos-tax (CAP:550 S32c)")
@SuppressWarnings("java:S6813")
class TaxRegistrationControllerTest {

    private static final String PATH = "/v1/accounting/tax-registrations";
    private static final UUID ACTOR = UUID.fromString("01990000-0000-7000-8000-0000000000e1");
    private static final UUID REGISTRATION_ID = UUID.fromString("01990000-0000-7000-8000-0000000000a1");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaxRegistrationClient client;

    @MockitoBean
    private ExtTaxRegistrationRepository registrations;

    private static Authentication caller(String... permissions) {
        return new UsernamePasswordAuthenticationToken(
                "controller-user",
                null,
                java.util.Arrays.stream(permissions)
                        .map(SimpleGrantedAuthority::new)
                        .toList());
    }

    private static String body(String justification) {
        return """
                {"countryCode":"CA","regime":"GST_HST","registrationNumber":"123456789 RT 0001",
                 "effectiveFrom":"2026-01-01","justification":"%s",
                 "requestId":"019a0000-0000-7000-8000-000000000201","actor":"someone-else"}
                """.formatted(justification);
    }

    private static TaxRegistrationClient.Registration registration() {
        return new TaxRegistrationClient.Registration(
                REGISTRATION_ID,
                "CA",
                "GST_HST",
                "123456789RT0001",
                "CA",
                LocalDate.of(2026, 1, 1),
                null,
                "ACTIVE",
                0L,
                Instant.parse("2026-10-08T12:00:00Z"));
    }

    @Test
    @DisplayName("AC 4: without accounting:tax_registration:manage the write is 403 and pos-tax is not called")
    void manageRequired() throws Exception {
        mockMvc.perform(post(PATH)
                        .with(authentication(caller(AccountingPermissions.TAX_REGISTRATION_VIEW)))
                        .header("X-User-Id", ACTOR.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Registered with the tax authority")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(PATH + "/" + REGISTRATION_ID)
                        .with(authentication(caller(AccountingPermissions.MAPPING_KEY_EDIT)))
                        .header("X-User-Id", ACTOR.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Registered with the tax authority")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("the front door's own checks: a short justification is 400 and pos-tax is not called")
    void justificationChecked() throws Exception {
        mockMvc.perform(post(PATH)
                        .with(authentication(caller(AccountingPermissions.TAX_REGISTRATION_MANAGE)))
                        .header("X-User-Id", ACTOR.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("too short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("justification"));

        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("AC 3: the write is passed on with the X-User-Id actor, never a body field, and answers 201")
    void recordsWithTheForwardedActor() throws Exception {
        when(client.create(any(), eq(ACTOR.toString())))
                .thenReturn(new TaxRegistrationClient.Written(registration(), false));

        mockMvc.perform(post(PATH)
                        .with(authentication(caller(AccountingPermissions.TAX_REGISTRATION_MANAGE)))
                        .header("X-User-Id", ACTOR.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Registered with the tax authority")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.registrationId").value(REGISTRATION_ID.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(client).create(any(), eq(ACTOR.toString()));
    }

    @Test
    @DisplayName("a request without a person's X-User-Id is 403 and pos-tax is not called")
    void personRequired() throws Exception {
        mockMvc.perform(post(PATH)
                        .with(authentication(caller(AccountingPermissions.TAX_REGISTRATION_MANAGE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Registered with the tax authority")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("AC 1: pos-tax's 409 TAX_REGISTRATION_OVERLAP is relayed unchanged")
    void overlapRelayed() throws Exception {
        when(client.create(any(), any()))
                .thenThrow(new TaxRegistrationRelayException(
                        409,
                        ApiError.of(
                                "TAX_REGISTRATION_OVERLAP",
                                "Another registration is in effect",
                                409,
                                "2026-10-08T12:00:00Z",
                                "corr-1")));

        mockMvc.perform(post(PATH)
                        .with(authentication(caller(AccountingPermissions.TAX_REGISTRATION_MANAGE)))
                        .header("X-User-Id", ACTOR.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Registered with the tax authority")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TAX_REGISTRATION_OVERLAP"))
                .andExpect(jsonPath("$.message").value("Another registration is in effect"));
    }

    @Test
    @DisplayName("AC 4: pos-tax unreachable is 503 SERVICE_UNAVAILABLE with Retry-After")
    void unavailable() throws Exception {
        when(client.update(any(), any(), any()))
                .thenThrow(new TaxServiceUnavailableException("The tax registry is unavailable"));

        mockMvc.perform(put(PATH + "/" + REGISTRATION_ID)
                        .with(authentication(caller(AccountingPermissions.TAX_REGISTRATION_MANAGE)))
                        .header("X-User-Id", ACTOR.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"registrationNumber":"123456789RT0001","effectiveFrom":"2026-01-01","version":0,
                                 "justification":"Deregistered at the end of May",
                                 "requestId":"019a0000-0000-7000-8000-000000000202"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("AC 4: GET with asOf returns the registration in effect that day, with its status on that day")
    void readsAsOf() throws Exception {
        when(registrations.findInEffectOn(LocalDate.of(2026, 6, 30)))
                .thenReturn(List.of(ExtTaxRegistration.builder()
                        .registrationId(REGISTRATION_ID)
                        .countryCode("CA")
                        .regime("GST_HST")
                        .registrationNumber("123456789RT0001")
                        .jurisdictionCode("CA")
                        .effectiveFrom(LocalDate.of(2026, 1, 1))
                        .effectiveTo(LocalDate.of(2026, 6, 30))
                        .aggregateVersion(1)
                        .changedAt(Instant.parse("2026-10-08T12:00:00Z"))
                        .syncedAt(Instant.parse("2026-10-08T12:00:01Z"))
                        .build()));

        mockMvc.perform(get(PATH)
                        .param("asOf", "2026-06-30")
                        .with(authentication(caller(AccountingPermissions.TAX_REGISTRATION_VIEW))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-06-30"))
                .andExpect(jsonPath("$.registrations[0].registrationId").value(REGISTRATION_ID.toString()))
                .andExpect(jsonPath("$.registrations[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.registrations[0].version").value(1));
        mockMvc.perform(get(PATH).with(authentication(caller(AccountingPermissions.MAPPING_KEY_VIEW))))
                .andExpect(status().isForbidden());
    }

    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @SuppressWarnings("java:S4502") // CSRF disabled: stateless slice, no cookies
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }
    }
}
