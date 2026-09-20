package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.mcp.internal.config.TranscriptionProperties;
import com.positivity.mcp.internal.dto.TranscriptionResponse;
import com.positivity.mcp.internal.exception.AudioTooLargeException;
import com.positivity.mcp.internal.exception.UnintelligibleAudioException;
import com.positivity.mcp.internal.exception.UnsupportedAudioException;
import com.positivity.mcp.internal.service.SpeechToTextClient.SpeechToTextResult;
import jakarta.validation.ConstraintViolationException;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

/**
 * Unit tests for {@link TranscriptionServiceImpl} (#2074): validation, effective/response
 * language precedence, and transcribe-and-discard logging.
 *
 * <p>{@link SpeechToTextClient} is mocked — the outbound call itself is {@link
 * com.positivity.mcp.internal.client.OpenAiSpeechToTextClientTest}'s job.
 */
class TranscriptionServiceImplTest {

    private static final TranscriptionProperties PROPERTIES = new TranscriptionProperties(
            "http://provider.example/v1", "test-key", "whisper-1", Duration.ofSeconds(30), DataSize.ofMegabytes(5), 60);

    private static final long MAX_BYTES = PROPERTIES.maxBytes().toBytes();

    private final SpeechToTextClient client = mock(SpeechToTextClient.class);
    private final TranscriptionServiceImpl service = new TranscriptionServiceImpl(client, PROPERTIES);

    private static MockMultipartFile audio(byte[] bytes, String contentType) {
        return new MockMultipartFile("audio", "clip.webm", contentType, bytes);
    }

    private static MockMultipartFile audio(int size, String contentType) {
        return audio(new byte[size], contentType);
    }

    // -- audio presence / size -------------------------------------------------------------

    @Test
    @DisplayName("null audio raises UnsupportedAudioException")
    void transcribe_nullAudio_throwsUnsupportedAudio() {
        assertThatThrownBy(() -> service.transcribe(null, null, Locale.forLanguageTag("und")))
                .isInstanceOf(UnsupportedAudioException.class);
    }

    @Test
    @DisplayName("empty audio raises UnsupportedAudioException")
    void transcribe_emptyAudio_throwsUnsupportedAudio() {
        MultipartFile empty = audio(0, "audio/webm");

        assertThatThrownBy(() -> service.transcribe(empty, null, Locale.forLanguageTag("und")))
                .isInstanceOf(UnsupportedAudioException.class);
    }

    @Test
    @DisplayName("audio exactly at the max size passes the size check")
    void transcribe_audioAtMaxSize_passes() {
        when(client.transcribe(any(), any(), any(), any())).thenReturn(new SpeechToTextResult("hi", "en", null));

        TranscriptionResponse response =
                service.transcribe(audio((int) MAX_BYTES, "audio/webm"), null, Locale.forLanguageTag("und"));

        assertThat(response.text()).isEqualTo("hi");
    }

    @Test
    @DisplayName("audio one byte over the max size raises AudioTooLargeException")
    void transcribe_audioOverMaxSize_throwsAudioTooLarge() {
        MultipartFile oversize = audio((int) MAX_BYTES + 1, "audio/webm");

        assertThatThrownBy(() -> service.transcribe(oversize, null, Locale.forLanguageTag("und")))
                .isInstanceOf(AudioTooLargeException.class);
    }

    // -- content type -------------------------------------------------------------------

    @Test
    @DisplayName("audio/webm;codecs=opus is accepted (parameters ignored)")
    void transcribe_webmWithCodecParam_isAccepted() {
        when(client.transcribe(any(), any(), any(), any())).thenReturn(new SpeechToTextResult("hi", "en", null));

        assertThat(service.transcribe(audio(new byte[] {1}, "audio/webm;codecs=opus"), null, Locale.ROOT)
                        .text())
                .isEqualTo("hi");
    }

    @Test
    @DisplayName("AUDIO/OGG is accepted case-insensitively")
    void transcribe_oggUpperCase_isAccepted() {
        when(client.transcribe(any(), any(), any(), any())).thenReturn(new SpeechToTextResult("hi", "en", null));

        assertThat(service.transcribe(audio(new byte[] {1}, "AUDIO/OGG"), null, Locale.ROOT)
                        .text())
                .isEqualTo("hi");
    }

    @Test
    @DisplayName("audio/wav is rejected as unsupported")
    void transcribe_wav_isRejected() {
        MultipartFile wav = audio(new byte[] {1}, "audio/wav");

        assertThatThrownBy(() -> service.transcribe(wav, null, Locale.ROOT))
                .isInstanceOf(UnsupportedAudioException.class);
    }

    @Test
    @DisplayName("video/webm is rejected as unsupported")
    void transcribe_videoWebm_isRejected() {
        MultipartFile videoWebm = audio(new byte[] {1}, "video/webm");

        assertThatThrownBy(() -> service.transcribe(videoWebm, null, Locale.ROOT))
                .isInstanceOf(UnsupportedAudioException.class);
    }

    @Test
    @DisplayName("the unsupported-content-type message does not echo the caller's content type back")
    void transcribe_unsupportedContentType_messageDoesNotEchoClientContentType() {
        MultipartFile videoWebm = audio(new byte[] {1}, "video/webm");

        assertThatThrownBy(() -> service.transcribe(videoWebm, null, Locale.ROOT))
                .isInstanceOf(UnsupportedAudioException.class)
                .extracting(Throwable::getMessage, org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .doesNotContain("video/webm");
    }

    // -- language ---------------------------------------------------------------------

    @Test
    @DisplayName("a malformed language tag raises ConstraintViolationException")
    void transcribe_malformedLanguage_throwsConstraintViolation() {
        MultipartFile clip = audio(new byte[] {1}, "audio/webm");

        assertThatThrownBy(() -> service.transcribe(clip, "not a tag!!", Locale.ROOT))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    @DisplayName("effective language: an explicit request parameter wins over the locale")
    void transcribe_effectiveLanguage_paramWinsOverLocale() {
        when(client.transcribe(any(), any(), any(), any())).thenReturn(new SpeechToTextResult("hi", "en", null));

        service.transcribe(audio(new byte[] {1}, "audio/webm"), "es-MX", Locale.forLanguageTag("fr-FR"));

        ArgumentCaptor<String> languageCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).transcribe(any(), any(), any(), languageCaptor.capture());
        assertThat(languageCaptor.getValue()).isEqualTo("es-MX");
    }

    @Test
    @DisplayName("effective language: falls back to the request locale when no param is given")
    void transcribe_effectiveLanguage_fallsBackToLocale() {
        when(client.transcribe(any(), any(), any(), any())).thenReturn(new SpeechToTextResult("hi", "en", null));

        service.transcribe(audio(new byte[] {1}, "audio/webm"), null, Locale.forLanguageTag("fr-FR"));

        ArgumentCaptor<String> languageCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).transcribe(any(), any(), any(), languageCaptor.capture());
        assertThat(languageCaptor.getValue()).isEqualTo("fr-FR");
    }

    @Test
    @DisplayName("effective language: null (provider auto-detect) when the locale is undetermined")
    void transcribe_effectiveLanguage_nullWhenLocaleUndetermined() {
        when(client.transcribe(any(), any(), any(), any())).thenReturn(new SpeechToTextResult("hi", "en", null));

        service.transcribe(audio(new byte[] {1}, "audio/webm"), null, Locale.forLanguageTag("und"));

        ArgumentCaptor<String> languageCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).transcribe(any(), any(), any(), languageCaptor.capture());
        assertThat(languageCaptor.getValue()).isNull();
    }

    // -- result mapping -----------------------------------------------------------------

    @Test
    @DisplayName("a blank transcript raises UnintelligibleAudioException")
    void transcribe_blankTranscript_throwsUnintelligibleAudio() {
        when(client.transcribe(any(), any(), any(), any())).thenReturn(new SpeechToTextResult("   ", "en", null));
        MultipartFile clip = audio(new byte[] {1}, "audio/webm");

        assertThatThrownBy(() -> service.transcribe(clip, null, Locale.ROOT))
                .isInstanceOf(UnintelligibleAudioException.class);
    }

    @Test
    @DisplayName("duration exactly at the max passes")
    void transcribe_durationAtMax_passes() {
        when(client.transcribe(any(), any(), any(), any())).thenReturn(new SpeechToTextResult("hi", "en", 60.0));

        TranscriptionResponse response = service.transcribe(audio(new byte[] {1}, "audio/webm"), null, Locale.ROOT);

        assertThat(response.durationSeconds()).isEqualTo(60.0);
    }

    @Test
    @DisplayName("duration over the max raises AudioTooLargeException")
    void transcribe_durationOverMax_throwsAudioTooLarge() {
        when(client.transcribe(any(), any(), any(), any())).thenReturn(new SpeechToTextResult("hi", "en", 60.01));
        MultipartFile clip = audio(new byte[] {1}, "audio/webm");

        assertThatThrownBy(() -> service.transcribe(clip, null, Locale.ROOT))
                .isInstanceOf(AudioTooLargeException.class);
    }

    @Test
    @DisplayName("a blank transcript for a clip over the duration max raises AudioTooLargeException (413), "
            + "not UnintelligibleAudioException (422) — the size/duration rejection wins over the "
            + "content-quality one")
    void transcribe_blankTranscriptOverDurationMax_throwsAudioTooLargeNotUnintelligible() {
        when(client.transcribe(any(), any(), any(), any())).thenReturn(new SpeechToTextResult("   ", "en", 61.0));
        MultipartFile clip = audio(new byte[] {1}, "audio/webm");

        assertThatThrownBy(() -> service.transcribe(clip, null, Locale.ROOT))
                .isInstanceOf(AudioTooLargeException.class);
    }

    @Test
    @DisplayName("response language: the client-reported language wins")
    void transcribe_responseLanguage_prefersClientReported() {
        when(client.transcribe(any(), any(), any(), any())).thenReturn(new SpeechToTextResult("hi", "de-DE", null));

        TranscriptionResponse response = service.transcribe(audio(new byte[] {1}, "audio/webm"), "fr-FR", Locale.ROOT);

        assertThat(response.language()).isEqualTo("de-DE");
    }

    @Test
    @DisplayName("response language: falls back to the effective language when the client reports none")
    void transcribe_responseLanguage_fallsBackToEffectiveLanguage() {
        when(client.transcribe(any(), any(), any(), any())).thenReturn(new SpeechToTextResult("hi", null, null));

        TranscriptionResponse response = service.transcribe(audio(new byte[] {1}, "audio/webm"), "fr-FR", Locale.ROOT);

        assertThat(response.language()).isEqualTo("fr-FR");
    }

    @Test
    @DisplayName("response language: \"und\" when neither the client nor the request named one")
    void transcribe_responseLanguage_undWhenNeitherKnown() {
        when(client.transcribe(any(), any(), any(), any())).thenReturn(new SpeechToTextResult("hi", null, null));

        TranscriptionResponse response =
                service.transcribe(audio(new byte[] {1}, "audio/webm"), null, Locale.forLanguageTag("und"));

        assertThat(response.language()).isEqualTo("und");
    }

    // -- filename -----------------------------------------------------------------------

    @Test
    @DisplayName("filename: the original filename is used when present")
    void transcribe_filename_usesOriginalFilenameWhenPresent() {
        when(client.transcribe(any(), any(), any(), any())).thenReturn(new SpeechToTextResult("hi", "en", null));
        MultipartFile clip = new MockMultipartFile("audio", "my-clip.ogg", "audio/ogg", new byte[] {1});

        service.transcribe(clip, null, Locale.ROOT);

        ArgumentCaptor<String> filenameCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).transcribe(any(), filenameCaptor.capture(), any(), any());
        assertThat(filenameCaptor.getValue()).isEqualTo("my-clip.ogg");
    }

    @Test
    @DisplayName("filename: falls back to clip.<ext> derived from the content type when absent")
    void transcribe_filename_fallsBackToClipDotExtension() {
        when(client.transcribe(any(), any(), any(), any())).thenReturn(new SpeechToTextResult("hi", "en", null));
        MultipartFile clip = new MockMultipartFile("audio", "", "audio/ogg", new byte[] {1});

        service.transcribe(clip, null, Locale.ROOT);

        ArgumentCaptor<String> filenameCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).transcribe(any(), filenameCaptor.capture(), any(), any());
        assertThat(filenameCaptor.getValue()).isEqualTo("clip.ogg");
    }

    // -- retention / logging --------------------------------------------------------------

    @Test
    @DisplayName("the completion log line never carries the transcript text or raw audio bytes")
    void transcribe_completionLog_neverCarriesAudioOrTranscriptContent() {
        String secretTranscript = "how many mechanics do i have";
        when(client.transcribe(any(), any(), any(), any()))
                .thenReturn(new SpeechToTextResult(secretTranscript, "en-US", 7.2));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TranscriptionServiceImpl.class);
        logger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.transcribe(audio(new byte[] {9, 9, 9}, "audio/webm"), null, Locale.ROOT);

            assertThat(appender.list).isNotEmpty();
            String line = appender.list.getFirst().getFormattedMessage();
            assertThat(line).doesNotContain(secretTranscript);
            assertThat(line)
                    .contains("sizeBytes=3")
                    .contains("mimeType=audio/webm")
                    .contains("language=en-US");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(null);
        }
    }

    @Test
    @DisplayName("the completion log line strips control characters and caps oversized provider/request values")
    void transcribe_completionLog_sanitizesControlCharactersAndCapsLength() {
        // A hostile provider language: CR/LF that would forge a second log line, other control
        // characters, and far more than the 100-character cap.
        String injected = "en-US\r\nFORGED level=ERROR\u0007" + "x".repeat(200);
        when(client.transcribe(any(), any(), any(), any()))
                .thenReturn(new SpeechToTextResult("some words", injected, 1.0));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TranscriptionServiceImpl.class);
        logger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.transcribe(audio(new byte[] {9, 9, 9}, "audio/webm"), null, Locale.ROOT);

            assertThat(appender.list).hasSize(1);
            String line = appender.list.getFirst().getFormattedMessage();
            assertThat(line).doesNotContain("\r").doesNotContain("\n").doesNotContain("\u0007");
            assertThat(line).contains("language=en-US__FORGED level=ERROR");
            // The 200 x's are cut at the cap, so the logged language ends in "..." before latencyMs.
            assertThat(line).doesNotContain("x".repeat(101)).contains("..., durationSeconds=1.0");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(null);
        }
    }
}
