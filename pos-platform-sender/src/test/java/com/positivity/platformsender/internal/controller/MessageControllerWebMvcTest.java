package com.positivity.platformsender.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.platformsender.internal.config.SecurityConfig;
import com.positivity.platformsender.internal.config.SenderExceptionHandler;
import com.positivity.platformsender.internal.config.SenderProperties;
import com.positivity.platformsender.internal.dto.SendMessageResponse;
import com.positivity.platformsender.internal.exception.MessageRefusedException;
import com.positivity.platformsender.internal.exception.SenderUnavailableException;
import com.positivity.platformsender.internal.security.SenderSecretFilter;
import com.positivity.platformsender.internal.service.MessageSendService;
import com.positivity.platformsender.internal.service.MessageSendService.SendResult;
import com.positivity.security.common.GatewayAuthoritiesFilter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The FI-2 §1 wire contract through the production security chains: the shared secret is the only
 * way in, and each service outcome becomes the status class the caller's retry logic reads (202,
 * 200 on replay, 422 permanent, 503 transient, 400 malformed).
 */
@WebMvcTest(MessageController.class)
@Import({SecurityConfig.class, SenderExceptionHandler.class, MessageControllerWebMvcTest.SliceConfig.class})
@TestPropertySource(properties = "pos.platform-sender.api-secret=sender-s3cret")
class MessageControllerWebMvcTest {

    private static final String BODY = """
            {"messageId":"01990000-0000-7000-8000-0000000000c1","channel":"EMAIL",
             "recipientPartyId":"01990000-0000-7000-8000-0000000000a1","contactId":null,
             "campaignCode":"SPRING24","subject":"Spring tyres","body":"Time for new tyres"}
            """;

    @TestConfiguration
    @EnableConfigurationProperties(SenderProperties.class)
    static class SliceConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC);
        }

        /** Keep GatewayAuthoritiesFilter inside its chain, as pos-tenant's internal-endpoint test does. */
        @Bean
        FilterRegistrationBean<GatewayAuthoritiesFilter> gatewayAuthoritiesFilterRegistration(
                GatewayAuthoritiesFilter gatewayAuthoritiesFilter) {
            var registration = new FilterRegistrationBean<>(gatewayAuthoritiesFilter);
            registration.setEnabled(false);
            return registration;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MessageSendService messageSendService;

    private static MockHttpServletRequestBuilder send(String secret) {
        MockHttpServletRequestBuilder request = post("/platform-sender/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY);
        return secret == null ? request : request.header(SenderSecretFilter.SECRET_HEADER, secret);
    }

    @Test
    void refusesWithoutTheSecret() throws Exception {
        mockMvc.perform(send(null))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.code").value(SenderSecretFilter.CODE_SECRET_INVALID));
        verifyNoInteractions(messageSendService);
    }

    @Test
    void refusesAWrongSecretAndGatewayHeadersAlone() throws Exception {
        mockMvc.perform(send("wrong")).andExpect(status().isUnauthorized());
        mockMvc.perform(send(null).header("X-User", "admin").header("X-Authorities", "marketing:campaign:send"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(messageSendService);
    }

    @Test
    void acceptsWith202() throws Exception {
        when(messageSendService.send(any()))
                .thenReturn(new SendResult(new SendMessageResponse("ses-1", "abc123"), false));

        mockMvc.perform(send("sender-s3cret"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.providerMessageId").value("ses-1"))
                .andExpect(jsonPath("$.addressHash").value("abc123"));
    }

    @Test
    void answersAReplayWith200() throws Exception {
        when(messageSendService.send(any()))
                .thenReturn(new SendResult(new SendMessageResponse("ses-1", "abc123"), true));

        mockMvc.perform(send("sender-s3cret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerMessageId").value("ses-1"));
    }

    @Test
    void aPermanentRefusalIs422() throws Exception {
        when(messageSendService.send(any()))
                .thenThrow(new MessageRefusedException("NO_CONTACT_POINT", "No deliverable EMAIL address"));

        mockMvc.perform(send("sender-s3cret"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("NO_CONTACT_POINT"))
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void aTransientFailureIs503() throws Exception {
        when(messageSendService.send(any())).thenThrow(new SenderUnavailableException("Throttling", "Rate exceeded"));

        mockMvc.perform(send("sender-s3cret"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("Throttling"));
    }

    @Test
    void aMalformedRequestIs400() throws Exception {
        mockMvc.perform(post("/platform-sender/v1/messages")
                        .header(SenderSecretFilter.SECRET_HEADER, "sender-s3cret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"EMAIL\",\"body\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(messageSendService);
    }
}
