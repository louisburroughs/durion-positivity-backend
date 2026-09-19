package com.positivity.mcp.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.audio.AudioResponseFormat;
import com.positivity.mcp.internal.config.TranscriptionProperties;
import com.positivity.mcp.internal.exception.TranscriptionUnavailableException;
import com.positivity.mcp.internal.exception.UnintelligibleAudioException;
import com.positivity.mcp.internal.service.SpeechToTextClient.SpeechToTextResult;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.transcription.AudioTranscription;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.ai.openai.metadata.OpenAiAudioTranscriptionResponseMetadata;
import org.springframework.util.unit.DataSize;

/**
 * Unit tests for {@link OpenAiSpeechToTextClient} (#2074): response mapping, language subtag
 * translation, provider language-name normalization, filename sanitisation, failure translation,
 * and the unconfigured/no-auth wiring paths.
 */
class OpenAiSpeechToTextClientTest {

    private static final Headers EMPTY_HEADERS = Headers.builder().build();

    private static OpenAiAudioTranscriptionOptions defaultOptions() {
        return OpenAiAudioTranscriptionOptions.builder()
                .baseUrl("http://provider.example/v1")
                .apiKey("test-key")
                .model("whisper-1")
                .timeout(Duration.ofSeconds(30))
                .maxRetries(0)
                .responseFormat(AudioResponseFormat.VERBOSE_JSON)
                .build();
    }

    private static AudioTranscriptionResponse responseWithMetadata(String text, Double duration, String language) {
        return new AudioTranscriptionResponse(
                new AudioTranscription(text),
                new OpenAiAudioTranscriptionResponseMetadata(duration, language, null, null, null));
    }

    // -- response mapping -----------------------------------------------------------------

    @Test
    @DisplayName("maps the transcript, verbose duration and detected language from the provider response")
    void transcribe_mapsTextDurationAndLanguageFromVerboseMetadata() {
        TranscriptionModel model = mock(TranscriptionModel.class);
        when(model.call(any())).thenReturn(responseWithMetadata("how many mechanics do i have", 7.2, "en"));
        OpenAiSpeechToTextClient client = new OpenAiSpeechToTextClient(model, defaultOptions());

        SpeechToTextResult result = client.transcribe(new byte[] {1, 2, 3}, "clip.webm", "audio/webm", "en-US");

        assertThat(result.text()).isEqualTo("how many mechanics do i have");
        assertThat(result.durationSeconds()).isEqualTo(7.2);
        assertThat(result.language()).isEqualTo("en-US");
    }

    @Test
    @DisplayName("a provider language reported as an English display name is normalized to its ISO code")
    void transcribe_providerLanguageEnglishName_normalizedToIsoCode() {
        TranscriptionModel model = mock(TranscriptionModel.class);
        when(model.call(any())).thenReturn(responseWithMetadata("bonjour", null, "french"));
        OpenAiSpeechToTextClient client = new OpenAiSpeechToTextClient(model, defaultOptions());

        SpeechToTextResult result = client.transcribe(new byte[] {1}, "clip.webm", "audio/webm", null);

        assertThat(result.language()).isEqualTo("fr");
    }

    @Test
    @DisplayName("no metadata: null duration and language")
    void transcribe_noMetadata_nullDurationAndLanguage() {
        TranscriptionModel model = mock(TranscriptionModel.class);
        when(model.call(any())).thenReturn(new AudioTranscriptionResponse(new AudioTranscription("hi")));
        OpenAiSpeechToTextClient client = new OpenAiSpeechToTextClient(model, defaultOptions());

        SpeechToTextResult result = client.transcribe(new byte[] {1}, "clip.webm", "audio/webm", null);

        assertThat(result.durationSeconds()).isNull();
        assertThat(result.language()).isNull();
    }

    // -- providerLanguage: ISO 639-1 primary subtag ----------------------------------------

    @Test
    @DisplayName("providerLanguage: a region-qualified tag is reduced to its primary subtag")
    void providerLanguage_reducesToPrimarySubtag() {
        assertThat(OpenAiSpeechToTextClient.providerLanguage("en-US")).isEqualTo("en");
        assertThat(OpenAiSpeechToTextClient.providerLanguage("fr-CA")).isEqualTo("fr");
    }

    @Test
    @DisplayName("providerLanguage: null, blank and undetermined tags all yield null (auto-detect)")
    void providerLanguage_nullBlankOrUndetermined_yieldsNull() {
        assertThat(OpenAiSpeechToTextClient.providerLanguage(null)).isNull();
        assertThat(OpenAiSpeechToTextClient.providerLanguage("  ")).isNull();
        assertThat(OpenAiSpeechToTextClient.providerLanguage("und")).isNull();
    }

    @Test
    @DisplayName("language sent to the provider is the ISO primary subtag, captured from the built prompt")
    void transcribe_sendsIsoPrimarySubtagToProvider() {
        TranscriptionModel model = mock(TranscriptionModel.class);
        when(model.call(any())).thenReturn(responseWithMetadata("hi", null, null));
        OpenAiSpeechToTextClient client = new OpenAiSpeechToTextClient(model, defaultOptions());

        client.transcribe(new byte[] {1}, "clip.webm", "audio/webm", "en-US");

        org.mockito.ArgumentCaptor<AudioTranscriptionPrompt> captor =
                org.mockito.ArgumentCaptor.forClass(AudioTranscriptionPrompt.class);
        org.mockito.Mockito.verify(model).call(captor.capture());
        OpenAiAudioTranscriptionOptions sentOptions =
                (OpenAiAudioTranscriptionOptions) captor.getValue().getOptions();
        assertThat(sentOptions.getLanguage()).isEqualTo("en");
    }

    // -- filename sanitisation / extension --------------------------------------------------

    @Test
    @DisplayName("providerFilename: a filename with a known extension is kept as-is (unsafe chars aside)")
    void providerFilename_knownExtension_isKept() {
        assertThat(OpenAiSpeechToTextClient.providerFilename("clip.webm", "audio/webm"))
                .isEqualTo("clip.webm");
    }

    @Test
    @DisplayName("providerFilename: unsafe characters are replaced")
    void providerFilename_unsafeCharacters_areReplaced() {
        assertThat(OpenAiSpeechToTextClient.providerFilename("my recording!.webm", "audio/webm"))
                .isEqualTo("my_recording_.webm");
    }

    @Test
    @DisplayName("providerFilename: an unrecognized or missing extension falls back to one derived from mimeType")
    void providerFilename_unknownExtension_derivesFromMimeType() {
        assertThat(OpenAiSpeechToTextClient.providerFilename("blob", "audio/webm;codecs=opus"))
                .isEqualTo("audio.webm");
        assertThat(OpenAiSpeechToTextClient.providerFilename("recording.blob", "audio/mp4"))
                .isEqualTo("audio.m4a");
    }

    @Test
    @DisplayName("providerFilename: an unmapped mimeType falls back to .webm")
    void providerFilename_unmappedMimeType_fallsBackToWebm() {
        assertThat(OpenAiSpeechToTextClient.providerFilename("blob", "application/octet-stream"))
                .isEqualTo("audio.webm");
    }

    // -- error mapping ----------------------------------------------------------------------

    @Test
    @DisplayName("provider 400 (BadRequestException) maps to UnintelligibleAudioException")
    void transcribe_provider400_mapsToUnintelligibleAudio() {
        TranscriptionModel model = mock(TranscriptionModel.class);
        when(model.call(any()))
                .thenThrow(BadRequestException.builder().headers(EMPTY_HEADERS).build());
        OpenAiSpeechToTextClient client = new OpenAiSpeechToTextClient(model, defaultOptions());

        assertThatThrownBy(() -> client.transcribe(new byte[] {1}, "clip.webm", "audio/webm", null))
                .isInstanceOf(UnintelligibleAudioException.class);
    }

    @Test
    @DisplayName("provider 401 maps to TranscriptionUnavailableException")
    void transcribe_provider401_mapsToTranscriptionUnavailable() {
        TranscriptionModel model = mock(TranscriptionModel.class);
        when(model.call(any()))
                .thenThrow(
                        UnauthorizedException.builder().headers(EMPTY_HEADERS).build());
        OpenAiSpeechToTextClient client = new OpenAiSpeechToTextClient(model, defaultOptions());

        assertThatThrownBy(() -> client.transcribe(new byte[] {1}, "clip.webm", "audio/webm", null))
                .isInstanceOf(TranscriptionUnavailableException.class);
    }

    @Test
    @DisplayName("provider 429 maps to TranscriptionUnavailableException")
    void transcribe_provider429_mapsToTranscriptionUnavailable() {
        TranscriptionModel model = mock(TranscriptionModel.class);
        when(model.call(any()))
                .thenThrow(RateLimitException.builder().headers(EMPTY_HEADERS).build());
        OpenAiSpeechToTextClient client = new OpenAiSpeechToTextClient(model, defaultOptions());

        assertThatThrownBy(() -> client.transcribe(new byte[] {1}, "clip.webm", "audio/webm", null))
                .isInstanceOf(TranscriptionUnavailableException.class);
    }

    @Test
    @DisplayName("provider 5xx maps to TranscriptionUnavailableException")
    void transcribe_provider5xx_mapsToTranscriptionUnavailable() {
        TranscriptionModel model = mock(TranscriptionModel.class);
        when(model.call(any()))
                .thenThrow(InternalServerException.builder()
                        .statusCode(500)
                        .headers(EMPTY_HEADERS)
                        .build());
        OpenAiSpeechToTextClient client = new OpenAiSpeechToTextClient(model, defaultOptions());

        assertThatThrownBy(() -> client.transcribe(new byte[] {1}, "clip.webm", "audio/webm", null))
                .isInstanceOf(TranscriptionUnavailableException.class);
    }

    @Test
    @DisplayName("provider unreachable / IO failure maps to TranscriptionUnavailableException")
    void transcribe_ioFailure_mapsToTranscriptionUnavailable() {
        TranscriptionModel model = mock(TranscriptionModel.class);
        when(model.call(any())).thenThrow(new OpenAIIoException("connection refused"));
        OpenAiSpeechToTextClient client = new OpenAiSpeechToTextClient(model, defaultOptions());

        assertThatThrownBy(() -> client.transcribe(new byte[] {1}, "clip.webm", "audio/webm", null))
                .isInstanceOf(TranscriptionUnavailableException.class);
    }

    @Test
    @DisplayName("an unexpected RuntimeException outside the SDK's error hierarchy maps to "
            + "TranscriptionUnavailableException, with the original exception as its cause")
    void transcribe_unexpectedRuntimeException_mapsToTranscriptionUnavailableWithCause() {
        TranscriptionModel model = mock(TranscriptionModel.class);
        RuntimeException unexpected = new IllegalStateException("unexpected response shape");
        when(model.call(any())).thenThrow(unexpected);
        OpenAiSpeechToTextClient client = new OpenAiSpeechToTextClient(model, defaultOptions());

        assertThatThrownBy(() -> client.transcribe(new byte[] {1}, "clip.webm", "audio/webm", null))
                .isInstanceOf(TranscriptionUnavailableException.class)
                .hasCause(unexpected);
    }

    // -- unconfigured / auth wiring -----------------------------------------------------------

    @Test
    @DisplayName("unconfigured (blank base-url) client reports isConfigured() == false and 503s on transcribe")
    void unconfigured_isNotConfiguredAndTranscribeThrows503() {
        TranscriptionProperties disabled = new TranscriptionProperties(
                null, null, "whisper-1", Duration.ofSeconds(30), DataSize.ofMegabytes(5), 60);
        OpenAiSpeechToTextClient client = new OpenAiSpeechToTextClient(disabled);

        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.transcribe(new byte[] {1}, "clip.webm", "audio/webm", null))
                .isInstanceOf(TranscriptionUnavailableException.class);
    }

    @Test
    @DisplayName("a blank api key builds no-auth options (empty string), not the OPENAI_API_KEY env fallback")
    void transcriptionOptions_blankApiKey_producesNoAuthEmptyString() {
        TranscriptionProperties properties = new TranscriptionProperties(
                "http://provider.example/v1", "", "whisper-1", Duration.ofSeconds(30), DataSize.ofMegabytes(5), 60);

        OpenAiAudioTranscriptionOptions options = OpenAiSpeechToTextClient.transcriptionOptions(properties);

        assertThat(options.getApiKey()).isEmpty();
    }

    @Test
    @DisplayName("a null api key also builds no-auth options (empty string)")
    void transcriptionOptions_nullApiKey_producesNoAuthEmptyString() {
        TranscriptionProperties properties = new TranscriptionProperties(
                "http://provider.example/v1", null, "whisper-1", Duration.ofSeconds(30), DataSize.ofMegabytes(5), 60);

        OpenAiAudioTranscriptionOptions options = OpenAiSpeechToTextClient.transcriptionOptions(properties);

        assertThat(options.getApiKey()).isEmpty();
    }

    @Test
    @DisplayName("TranscriptionProperties.toString() never contains the configured api key")
    void transcriptionProperties_toString_neverContainsApiKey() {
        String secretKey = "sk-super-secret-do-not-log-me";
        TranscriptionProperties properties = new TranscriptionProperties(
                "http://provider.example/v1",
                secretKey,
                "whisper-1",
                Duration.ofSeconds(30),
                DataSize.ofMegabytes(5),
                60);

        String rendered = properties.toString();

        assertThat(rendered).doesNotContain(secretKey);
    }
}
