package com.positivity.mcp.internal.client;

import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.audio.AudioResponseFormat;
import com.positivity.mcp.internal.config.TranscriptionProperties;
import com.positivity.mcp.internal.exception.TranscriptionUnavailableException;
import com.positivity.mcp.internal.exception.UnintelligibleAudioException;
import com.positivity.mcp.internal.service.SpeechToTextClient;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.audio.transcription.AudioTranscription;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.ai.openai.metadata.OpenAiAudioTranscriptionResponseMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

/**
 * #2074: {@link SpeechToTextClient} over Spring AI's OpenAI transcription model ({@code
 * OpenAiAudioTranscriptionModel}, official {@code openai-java} SDK underneath), aimed at any
 * OpenAI-compatible {@code POST {base-url}/audio/transcriptions}.
 *
 * <p>Requests use {@code response_format=verbose_json}: it is the only format whose response
 * carries the clip {@code duration} and detected {@code language}, which Spring AI exposes through
 * {@link OpenAiAudioTranscriptionResponseMetadata}.
 *
 * <p>Failure translation (the SDK throws {@code com.openai.errors.*}, all unchecked):
 *
 * <ul>
 *   <li>{@link OpenAIServiceException} with status 400 ({@code BadRequestException}): the provider
 *       rejected the clip itself (undecodable, empty, wrong container) → {@link
 *       UnintelligibleAudioException} (422).
 *   <li>any other {@link OpenAIServiceException} (401/403/404/429/5xx, unexpected status) → {@link
 *       TranscriptionUnavailableException} (503).
 *   <li>{@link OpenAIIoException} (connection refused, DNS, socket/call timeout; OkHttp
 *       {@code IOException}s are wrapped by Spring AI's HTTP client) and {@link
 *       OpenAIRetryableException} → 503.
 *   <li>any other {@link OpenAIException} (e.g. {@code OpenAIInvalidDataException}, an unparseable
 *       provider response) → 503.
 * </ul>
 *
 * <p>Neither the audio nor the API key is ever logged; failures log the provider status (or the
 * failure kind) and the call latency only. The audio is wrapped in a per-call resource and nothing
 * here retains it after the call returns.
 *
 * <p>Wiring: a {@code @Component} built from {@link TranscriptionProperties}, independent of the
 * Ollama chat and embedding configuration. The module depends on the plain {@code spring-ai-openai}
 * library, not the OpenAI starter, so no OpenAI chat, embedding, or audio auto-configuration exists
 * and Ollama stays the only {@code ChatModel}/{@code EmbeddingModel}. The transcription model is
 * built here by hand and is not a bean. When {@code mcp.transcription.base-url} is blank the
 * component still exists, reports {@code isConfigured() == false}, and fails every call with 503.
 */
@Component
public class OpenAiSpeechToTextClient implements SpeechToTextClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiSpeechToTextClient.class);

    /** Extensions the OpenAI transcription endpoint and faster-whisper accept. */
    private static final Set<String> KNOWN_EXTENSIONS =
            Set.of("webm", "ogg", "oga", "opus", "mp4", "m4a", "mp3", "mpeg", "mpga", "wav", "flac");

    private static final Map<String, String> EXTENSION_BY_MIME_TYPE = Map.of(
            "audio/webm", "webm",
            "audio/ogg", "ogg",
            "audio/mp4", "m4a",
            "audio/mpeg", "mp3",
            "audio/wav", "wav",
            "audio/x-wav", "wav",
            "audio/flac", "flac");

    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[^A-Za-z0-9._-]");
    private static final Pattern ISO_LANGUAGE_CODE = Pattern.compile("[a-zA-Z]{2,3}");

    /**
     * OpenAI's Whisper reports the detected language as a lower-case English name ({@code
     * "english"}), self-hosted servers usually as an ISO 639-1 code ({@code "en"}); this maps the
     * names back to codes.
     */
    private static final Map<String, String> LANGUAGE_CODE_BY_ENGLISH_NAME = buildLanguageNameIndex();

    /**
     * Retries are off: a retried upload multiplies the user's wait past the composer's patience, and
     * the caller can simply record again.
     */
    private static final int MAX_RETRIES = 0;

    private final @Nullable TranscriptionModel model;
    private final @Nullable OpenAiAudioTranscriptionOptions defaultOptions;

    /**
     * Builds the provider model when {@code mcp.transcription.base-url} is set; otherwise the client
     * is unconfigured and every call fails with 503.
     */
    @Autowired
    public OpenAiSpeechToTextClient(@NonNull TranscriptionProperties properties) {
        if (!properties.isEnabled()) {
            log.info("Speech-to-text disabled: mcp.transcription.base-url is not set");
            this.model = null;
            this.defaultOptions = null;
            return;
        }
        OpenAiAudioTranscriptionOptions options = transcriptionOptions(properties);
        this.model = OpenAiAudioTranscriptionModel.builder().options(options).build();
        this.defaultOptions = options;
        log.info(
                "Speech-to-text enabled: model={} timeout={} auth={}",
                properties.model(),
                properties.timeout(),
                options.getApiKey() == null || options.getApiKey().isEmpty() ? "none" : "bearer");
    }

    /**
     * Test seam: a client over a given model.
     *
     * @param model the transcription model
     * @param defaultOptions the options the model was built with; each call copies them and adds
     *     the language, because Spring AI's options merge would otherwise reset the model, response
     *     format, and timeout to library defaults
     */
    OpenAiSpeechToTextClient(
            @NonNull TranscriptionModel model, @NonNull OpenAiAudioTranscriptionOptions defaultOptions) {
        this.model = Objects.requireNonNull(model, "model");
        this.defaultOptions = Objects.requireNonNull(defaultOptions, "defaultOptions");
    }

    static @NonNull OpenAiAudioTranscriptionOptions transcriptionOptions(@NonNull TranscriptionProperties properties) {
        String baseUrl = properties.baseUrl();
        String apiKey = properties.apiKey();
        return OpenAiAudioTranscriptionOptions.builder()
                .baseUrl(baseUrl == null ? null : baseUrl.strip())
                // Blank key -> "" -> Spring AI's no-auth mode (no Authorization header). Passing null
                // instead would make Spring AI fall back to the OPENAI_API_KEY environment variable
                // and send that key to whatever base-url is configured.
                .apiKey(apiKey == null || apiKey.isBlank() ? "" : apiKey.strip())
                .model(properties.model())
                .timeout(properties.timeout())
                .maxRetries(MAX_RETRIES)
                // verbose_json is the only format that reports the clip duration and language.
                .responseFormat(AudioResponseFormat.VERBOSE_JSON)
                .build();
    }

    @Override
    public boolean isConfigured() {
        return model != null;
    }

    @Override
    public @NonNull SpeechToTextResult transcribe(
            byte @NonNull [] audio, @NonNull String filename, @NonNull String mimeType, @Nullable String language) {
        TranscriptionModel transcriptionModel = model;
        OpenAiAudioTranscriptionOptions defaults = defaultOptions;
        if (transcriptionModel == null || defaults == null) {
            throw new TranscriptionUnavailableException("Speech-to-text is not configured on this server");
        }

        String providerLanguage = providerLanguage(language);
        OpenAiAudioTranscriptionOptions options = OpenAiAudioTranscriptionOptions.builder()
                .from(defaults)
                .language(providerLanguage)
                .build();
        AudioTranscriptionPrompt prompt = new AudioTranscriptionPrompt(
                new NamedAudioResource(audio, providerFilename(filename, mimeType)), options);

        long startNanos = System.nanoTime();
        AudioTranscriptionResponse response;
        try {
            response = transcriptionModel.call(prompt);
        } catch (OpenAIServiceException e) {
            int status = e.statusCode();
            log.warn("Speech-to-text provider answered status={} latencyMs={}", status, elapsedMillis(startNanos));
            if (status == 400) {
                throw new UnintelligibleAudioException(
                        "The speech-to-text provider could not process the audio clip", e);
            }
            throw new TranscriptionUnavailableException(
                    "The speech-to-text provider is unavailable (HTTP " + status + ")", e);
        } catch (OpenAIIoException | OpenAIRetryableException e) {
            log.warn(
                    "Speech-to-text provider unreachable or timed out: failure={} latencyMs={}",
                    e.getClass().getSimpleName(),
                    elapsedMillis(startNanos));
            throw new TranscriptionUnavailableException("The speech-to-text provider is unreachable or timed out", e);
        } catch (OpenAIException e) {
            log.warn(
                    "Speech-to-text provider call failed: failure={} latencyMs={}",
                    e.getClass().getSimpleName(),
                    elapsedMillis(startNanos));
            throw new TranscriptionUnavailableException("The speech-to-text provider returned an unusable response", e);
        } catch (RuntimeException e) {
            // Never-500 backstop for anything outside the SDK's error hierarchy (e.g. a Spring AI
            // assertion or an unexpected response shape). The try block holds only the provider call,
            // so no exception the transcription service relies on can be caught here. The exception
            // message is not logged: it could echo provider output.
            log.warn(
                    "Speech-to-text call failed unexpectedly: failure={} latencyMs={}",
                    e.getClass().getSimpleName(),
                    elapsedMillis(startNanos));
            throw new TranscriptionUnavailableException("The speech-to-text call failed unexpectedly", e);
        }
        log.debug("Speech-to-text provider answered status=200 latencyMs={}", elapsedMillis(startNanos));

        AudioTranscription result = response.getResult();
        String text = result != null && result.getOutput() != null
                ? result.getOutput().strip()
                : "";
        Double duration = null;
        String reportedLanguage = null;
        if (response.getMetadata() instanceof OpenAiAudioTranscriptionResponseMetadata metadata) {
            duration = validDuration(metadata.getDuration());
            reportedLanguage = metadata.getLanguage();
        }
        return new SpeechToTextResult(text, resultLanguage(language, providerLanguage, reportedLanguage), duration);
    }

    /**
     * The provider takes an ISO 639-1 code ({@code "en"}), not a full BCP-47 tag ({@code "en-US"}),
     * and rejects anything else with 400; null (auto-detect) when there is no usable primary subtag.
     */
    static @Nullable String providerLanguage(@Nullable String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String primary = Locale.forLanguageTag(language.strip()).getLanguage();
        return primary.length() == 2 ? primary : null;
    }

    /**
     * Keeps the caller's full tag (region included) when the provider agrees with it or reports
     * nothing, otherwise the provider's detected language as an ISO code; null when neither is known.
     */
    static @Nullable String resultLanguage(
            @Nullable String requested, @Nullable String providerLanguage, @Nullable String reported) {
        String reportedCode = normalizeReportedLanguage(reported);
        if (providerLanguage != null
                && requested != null
                && (reportedCode == null || reportedCode.equals(providerLanguage))) {
            return requested.strip();
        }
        return reportedCode;
    }

    static @Nullable String normalizeReportedLanguage(@Nullable String reported) {
        if (reported == null || reported.isBlank()) {
            return null;
        }
        String value = reported.strip().toLowerCase(Locale.ROOT);
        if (ISO_LANGUAGE_CODE.matcher(value).matches()) {
            String code = Locale.forLanguageTag(value).getLanguage();
            return code.isEmpty() ? null : code;
        }
        return LANGUAGE_CODE_BY_ENGLISH_NAME.get(value);
    }

    /**
     * Providers infer the container from the file extension, so the extension sent must always
     * match {@code mimeType} — the type this call was already validated against — never a
     * caller-supplied name that could disagree with it (a browser can send any filename alongside
     * any content type). The base name is kept from {@code filename} only when it already carries
     * one of {@link #KNOWN_EXTENSIONS} (proof it is a real name, not the browser's generic {@code
     * "blob"} for an extensionless {@code MediaRecorder} clip); otherwise the base name falls back
     * to {@code "audio"}. The name is also reduced to safe characters: it is sent to a third party
     * in a multipart header.
     */
    static @NonNull String providerFilename(@NonNull String filename, @NonNull String mimeType) {
        String safe = UNSAFE_FILENAME_CHARS.matcher(filename.strip()).replaceAll("_");
        int dot = safe.lastIndexOf('.');
        boolean hasKnownExtension = dot > 0
                && dot < safe.length() - 1
                && KNOWN_EXTENSIONS.contains(safe.substring(dot + 1).toLowerCase(Locale.ROOT));
        String base = hasKnownExtension ? safe.substring(0, dot) : "audio";
        return base + "." + extensionForMimeType(mimeType);
    }

    private static @NonNull String extensionForMimeType(@NonNull String mimeType) {
        int semicolon = mimeType.indexOf(';');
        String baseType = (semicolon >= 0 ? mimeType.substring(0, semicolon) : mimeType)
                .strip()
                .toLowerCase(Locale.ROOT);
        return EXTENSION_BY_MIME_TYPE.getOrDefault(baseType, "webm");
    }

    private static @Nullable Double validDuration(@Nullable Double duration) {
        return duration != null && Double.isFinite(duration) && duration >= 0 ? duration : null;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static Map<String, String> buildLanguageNameIndex() {
        Map<String, String> index = new HashMap<>();
        for (String code : Locale.getISOLanguages()) {
            if (code.length() != 2) {
                continue;
            }
            String name = Locale.of(code).getDisplayLanguage(Locale.ENGLISH).toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && !name.equals(code)) {
                index.putIfAbsent(name, code);
            }
        }
        return Map.copyOf(index);
    }

    /**
     * In-memory audio with a filename (a plain {@link ByteArrayResource} has none, and the SDK would
     * then send {@code "audio"}, which providers cannot map to a container). Scoped to one call.
     */
    private static final class NamedAudioResource extends ByteArrayResource {

        private final String filename;

        NamedAudioResource(byte[] audio, String filename) {
            super(audio, "speech-to-text audio clip");
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }
}
