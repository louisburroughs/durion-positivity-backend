package com.positivity.inboundmail.internal.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inboundmail.internal.config.InboundMailExceptionHandler;
import com.positivity.inboundmail.internal.config.InboundMailProperties;
import com.positivity.inboundmail.internal.config.SecurityConfig;
import com.positivity.inboundmail.internal.dto.StagedAttachmentBytes;
import com.positivity.inboundmail.internal.security.InboundMailSecretFilter;
import com.positivity.inboundmail.internal.service.AttachmentStaging;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The fetch contract: bytes untouched with the integrity headers, and one identical 404 for every unreadable ref. */
@WebMvcTest(StagedAttachmentController.class)
@Import({SecurityConfig.class, InboundMailExceptionHandler.class, StagedAttachmentControllerWebMvcTest.SliceConfig.class})
@TestPropertySource(properties = "pos.inbound-mail.api-secret=edge-s3cret")
class StagedAttachmentControllerWebMvcTest {

    private static final String SHA = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @TestConfiguration
    @EnableConfigurationProperties(InboundMailProperties.class)
    static class SliceConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AttachmentStaging attachmentStaging;

    @Test
    void streamsTheBytesWithTheIntegrityHeaders() throws Exception {
        UUID ref = UUID.randomUUID();
        when(attachmentStaging.fetch(ref))
                .thenReturn(Optional.of(new StagedAttachmentBytes("hello".getBytes(StandardCharsets.UTF_8), 5, SHA)));

        mockMvc.perform(get("/inbound-mail/v1/staged-attachments/{ref}", ref)
                        .header(InboundMailSecretFilter.SECRET_HEADER, "edge-s3cret"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/octet-stream"))
                .andExpect(header().string("X-Content-SHA256", SHA))
                .andExpect(header().string("Content-Length", "5"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void everyUnreadableRefAnswersTheSameBody() throws Exception {
        UUID foreignOrUnknown = UUID.randomUUID();
        UUID collectedOrPurged = UUID.randomUUID();
        when(attachmentStaging.fetch(foreignOrUnknown)).thenReturn(Optional.empty());
        when(attachmentStaging.fetch(collectedOrPurged)).thenReturn(Optional.empty());

        MvcResult first = mockMvc.perform(get("/inbound-mail/v1/staged-attachments/{ref}", foreignOrUnknown)
                        .header(InboundMailSecretFilter.SECRET_HEADER, "edge-s3cret"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STAGED_ATTACHMENT_NOT_FOUND"))
                .andReturn();
        MvcResult second = mockMvc.perform(get("/inbound-mail/v1/staged-attachments/{ref}", collectedOrPurged)
                        .header(InboundMailSecretFilter.SECRET_HEADER, "edge-s3cret"))
                .andExpect(status().isNotFound())
                .andReturn();

        String firstBody = first.getResponse().getContentAsString().replaceAll("\"(timestamp|correlationId)\":\"[^\"]*\"", "");
        String secondBody = second.getResponse().getContentAsString().replaceAll("\"(timestamp|correlationId)\":\"[^\"]*\"", "");
        org.assertj.core.api.Assertions.assertThat(firstBody).isEqualTo(secondBody);
        org.assertj.core.api.Assertions.assertThat(firstBody).doesNotContain(foreignOrUnknown.toString());
    }

    @Test
    void refusesWithoutTheSecret() throws Exception {
        mockMvc.perform(get("/inbound-mail/v1/staged-attachments/{ref}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_INBOUND_MAIL_SECRET"));
        verifyNoInteractions(attachmentStaging);
    }
}
