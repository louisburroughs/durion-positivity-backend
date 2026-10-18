package com.positivity.inboundmail.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inboundmail.internal.config.InboundMailExceptionHandler;
import com.positivity.inboundmail.internal.config.InboundMailProperties;
import com.positivity.inboundmail.internal.config.SecurityConfig;
import com.positivity.inboundmail.internal.dto.IssuedAddressResponse;
import com.positivity.inboundmail.internal.security.InboundMailSecretFilter;
import com.positivity.inboundmail.internal.service.AddressRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The address API through the production security chain: the shared secret is the only way in. */
@WebMvcTest(AddressController.class)
@Import({SecurityConfig.class, InboundMailExceptionHandler.class, AddressControllerWebMvcTest.SliceConfig.class})
@TestPropertySource(properties = "pos.inbound-mail.api-secret=edge-s3cret")
class AddressControllerWebMvcTest {

    private static final UUID ADDRESS_ID = UUID.fromString("01990000-0000-7000-8000-0000000000a1");
    private static final Instant ISSUED = Instant.parse("2026-10-17T12:00:00Z");
    private static final String ADDRESS = "bills-aaaaaaaaaaaaaaaaaaaaaaaaaa@mail.example.com";

    @TestConfiguration
    @EnableConfigurationProperties(InboundMailProperties.class)
    static class SliceConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(ISSUED, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AddressRegistry addressRegistry;

    @Test
    void refusesWithoutTheSecretAndWithGatewayHeadersAlone() throws Exception {
        mockMvc.perform(post("/inbound-mail/v1/addresses"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_INBOUND_MAIL_SECRET"));
        mockMvc.perform(post("/inbound-mail/v1/addresses")
                        .header("X-User", "admin")
                        .header("X-Authorities", "accounting:bill_intake:manage"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/inbound-mail/v1/addresses").header(InboundMailSecretFilter.SECRET_HEADER, "wrong"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(addressRegistry);
    }

    @Test
    void issuesWithTheSecret() throws Exception {
        when(addressRegistry.issueOrGet()).thenReturn(new IssuedAddressResponse(ADDRESS_ID, ADDRESS, ISSUED));

        mockMvc.perform(post("/inbound-mail/v1/addresses").header(InboundMailSecretFilter.SECRET_HEADER, "edge-s3cret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addressId").value(ADDRESS_ID.toString()))
                .andExpect(jsonPath("$.address").value(ADDRESS))
                .andExpect(jsonPath("$.issuedAt").value("2026-10-17T12:00:00Z"));
    }

    @Test
    void rotatesOnTheCallersRequestId() throws Exception {
        UUID requestId = UUID.fromString("01990000-0000-7000-8000-0000000000b1");
        when(addressRegistry.rotate(requestId)).thenReturn(new IssuedAddressResponse(ADDRESS_ID, ADDRESS, ISSUED));

        mockMvc.perform(post("/inbound-mail/v1/addresses/rotation")
                        .header(InboundMailSecretFilter.SECRET_HEADER, "edge-s3cret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addressId").value(ADDRESS_ID.toString()));
        verify(addressRegistry).rotate(requestId);
    }

    @Test
    void aRotationWithoutARequestIdIs400() throws Exception {
        mockMvc.perform(post("/inbound-mail/v1/addresses/rotation")
                        .header(InboundMailSecretFilter.SECRET_HEADER, "edge-s3cret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(addressRegistry);
    }

    @Test
    void rotationRefusesWithoutTheSecret() throws Exception {
        mockMvc.perform(post("/inbound-mail/v1/addresses/rotation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized());
        verify(addressRegistry, org.mockito.Mockito.never()).rotate(any());
    }
}
