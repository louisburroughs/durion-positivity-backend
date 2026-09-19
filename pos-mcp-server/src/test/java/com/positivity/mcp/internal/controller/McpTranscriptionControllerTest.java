package com.positivity.mcp.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.mcp.internal.dto.TranscriptionResponse;
import com.positivity.mcp.internal.exception.AudioTooLargeException;
import com.positivity.mcp.internal.exception.TranscriptionUnavailableException;
import com.positivity.mcp.internal.exception.UnintelligibleAudioException;
import com.positivity.mcp.internal.exception.UnsupportedAudioException;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.internal.service.TranscriptionService;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import jakarta.validation.ConstraintViolationException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller slice tests for {@link McpTranscriptionController} (#2074).
 *
 * <p>{@link TranscriptionService} is fully mocked: this class proves the HTTP contract — status
 * codes, JSON shape, the exception-to-status mapping ({@link McpTranscriptionExceptionHandler}
 * and, for the pre-dispatch cases, {@link McpTranscriptionPreDispatchExceptionHandler}, both
 * auto-detected by {@code @WebMvcTest} as {@code @ControllerAdvice} beans), the
 * {@code mcp:chat:execute} permission gate, and that the resolved request locale reaches the
 * service. Both {@code audio} and {@code language} are {@code @RequestPart}s (multipart form
 * fields), not query parameters — {@code language} is sent via {@link MockPart}, not
 * {@code .param(...)}.
 */
@WebMvcTest(McpTranscriptionController.class)
@Import(WebCommonErrorAutoConfiguration.class)
@ActiveProfiles("test")
class McpTranscriptionControllerTest {

    private static final String BASE = "/v1/mcp/transcriptions";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TranscriptionService transcriptionService;

    private static MockMultipartFile audioPart() {
        return new MockMultipartFile("audio", "clip.webm", "audio/webm;codecs=opus", new byte[] {1, 2, 3});
    }

    private static MockPart languagePart(String value) {
        return new MockPart("language", value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("200: happy path returns text, language and durationSeconds")
    void transcribe_happyPath_returns200WithTranscript() throws Exception {
        when(transcriptionService.transcribe(any(), eq((String) null), any(Locale.class)))
                .thenReturn(new TranscriptionResponse("how many mechanics do i have", "en-US", 7.2));

        mockMvc.perform(multipart(BASE).file(audioPart()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.text").value("how many mechanics do i have"))
                .andExpect(jsonPath("$.language").value("en-US"))
                .andExpect(jsonPath("$.durationSeconds").value(7.2));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("200: a null provider-reported duration is omitted, not serialized as null")
    void transcribe_nullDuration_isOmittedFromResponse() throws Exception {
        when(transcriptionService.transcribe(any(), eq((String) null), any(Locale.class)))
                .thenReturn(new TranscriptionResponse("hello", "und", null));

        mockMvc.perform(multipart(BASE).file(audioPart()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationSeconds").doesNotExist());
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("200: an explicit language multipart part reaches the service")
    void transcribe_languagePart_reachesService() throws Exception {
        when(transcriptionService.transcribe(any(), eq("es-MX"), any(Locale.class)))
                .thenReturn(new TranscriptionResponse("hola", "es-MX", null));

        mockMvc.perform(multipart(BASE)
                        .file(audioPart())
                        .part(languagePart("es-MX"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("es-MX"));

        verify(transcriptionService).transcribe(any(), eq("es-MX"), any(Locale.class));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("200: the request's resolved Accept-Language locale reaches the service")
    void transcribe_acceptLanguageHeader_resolvedLocaleReachesService() throws Exception {
        when(transcriptionService.transcribe(any(), eq((String) null), any(Locale.class)))
                .thenReturn(new TranscriptionResponse("bonjour", "fr-FR", null));

        mockMvc.perform(multipart(BASE)
                        .file(audioPart())
                        .header("Accept-Language", "fr-FR")
                        .with(csrf()))
                .andExpect(status().isOk());

        ArgumentCaptor<Locale> localeCaptor = ArgumentCaptor.forClass(Locale.class);
        verify(transcriptionService).transcribe(any(), eq((String) null), localeCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(localeCaptor.getValue()).isEqualTo(Locale.forLanguageTag("fr-FR"));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("200: an absent Accept-Language header resolves to Locale.ROOT (\"und\"), not the container "
            + "default — the request's implicit Locale resolution would otherwise fall back to the server's "
            + "own default locale rather than an undetermined one")
    void transcribe_absentAcceptLanguageHeader_resolvesToUndeterminedLocale() throws Exception {
        when(transcriptionService.transcribe(any(), eq((String) null), any(Locale.class)))
                .thenReturn(new TranscriptionResponse("hello", "und", null));

        mockMvc.perform(multipart(BASE).file(audioPart()).with(csrf())).andExpect(status().isOk());

        ArgumentCaptor<Locale> localeCaptor = ArgumentCaptor.forClass(Locale.class);
        verify(transcriptionService).transcribe(any(), eq((String) null), localeCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(localeCaptor.getValue()).isEqualTo(Locale.ROOT);
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("413: AudioTooLargeException maps to AUDIO_TOO_LARGE")
    void transcribe_audioTooLarge_returns413() throws Exception {
        when(transcriptionService.transcribe(any(), any(), any(Locale.class)))
                .thenThrow(new AudioTooLargeException("audio exceeds the maximum size"));

        mockMvc.perform(multipart(BASE).file(audioPart()).with(csrf()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("AUDIO_TOO_LARGE"))
                .andExpect(jsonPath("$.status").value(413));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("415: UnsupportedAudioException maps to UNSUPPORTED_AUDIO")
    void transcribe_unsupportedAudio_returns415() throws Exception {
        when(transcriptionService.transcribe(any(), any(), any(Locale.class)))
                .thenThrow(new UnsupportedAudioException("audio content type must be one of ..."));

        mockMvc.perform(multipart(BASE).file(audioPart()).with(csrf()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_AUDIO"))
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("422: UnintelligibleAudioException maps to UNINTELLIGIBLE_AUDIO")
    void transcribe_unintelligibleAudio_returns422() throws Exception {
        when(transcriptionService.transcribe(any(), any(), any(Locale.class)))
                .thenThrow(new UnintelligibleAudioException("transcription produced no usable text"));

        mockMvc.perform(multipart(BASE).file(audioPart()).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNINTELLIGIBLE_AUDIO"))
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("503: a transient TranscriptionUnavailableException (cause set) carries Retry-After: 30")
    void transcribe_providerTransientlyUnavailable_returns503WithRetryAfter() throws Exception {
        when(transcriptionService.transcribe(any(), any(), any(Locale.class)))
                .thenThrow(new TranscriptionUnavailableException(
                        "The speech-to-text provider is unreachable or timed out", new RuntimeException("timeout")));

        mockMvc.perform(multipart(BASE).file(audioPart()).with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TRANSCRIPTION_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(header().string("Retry-After", "30"));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("503: a not-configured TranscriptionUnavailableException (no cause) carries no Retry-After")
    void transcribe_providerNotConfigured_returns503WithoutRetryAfter() throws Exception {
        when(transcriptionService.transcribe(any(), any(), any(Locale.class)))
                .thenThrow(new TranscriptionUnavailableException("Speech-to-text is not configured on this server"));

        mockMvc.perform(multipart(BASE).file(audioPart()).with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TRANSCRIPTION_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("400: a malformed BCP-47 language raises ConstraintViolationException, mapped to VALIDATION_ERROR")
    void transcribe_malformedLanguage_returns400() throws Exception {
        when(transcriptionService.transcribe(any(), any(), any(Locale.class)))
                .thenThrow(new ConstraintViolationException("language must be a well-formed BCP-47 tag", Set.of()));

        mockMvc.perform(multipart(BASE)
                        .file(audioPart())
                        .part(languagePart("???"))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("415: the required audio part missing is rejected by the handler, without reaching the service")
    void transcribe_missingAudioPart_returns415() throws Exception {
        // `audio` is now @RequestPart("audio") (required), so a missing part is rejected by
        // MissingServletRequestPartException resolution before the service is ever called — proven
        // by verifyNoInteractions below, not by a stubbed service response.
        mockMvc.perform(multipart(BASE).part(languagePart("en-US")).with(csrf()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_AUDIO"));

        verifyNoInteractions(transcriptionService);
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("415: a non-multipart request returns UNSUPPORTED_AUDIO")
    void transcribe_nonMultipartRequest_returns415() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(csrf()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_AUDIO"));
    }

    @Test
    @WithMockUser(authorities = "ROLE_USER")
    @DisplayName("403: authenticated caller without mcp:chat:execute is denied with the FORBIDDEN envelope")
    void transcribe_withoutChatExecuteAuthority_returns403() throws Exception {
        mockMvc.perform(multipart(BASE).file(audioPart()).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("401: unauthenticated caller is rejected with the UNAUTHORIZED envelope")
    void transcribe_unauthenticated_returns401() throws Exception {
        mockMvc.perform(multipart(BASE).file(audioPart()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {}
}
