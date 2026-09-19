package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.config.TranscriptionProperties;
import com.positivity.mcp.internal.dto.TranscriptionResponse;
import com.positivity.mcp.internal.exception.AudioTooLargeException;
import com.positivity.mcp.internal.exception.UnintelligibleAudioException;
import com.positivity.mcp.internal.exception.UnsupportedAudioException;
import com.positivity.mcp.internal.service.SpeechToTextClient.SpeechToTextResult;
import jakarta.validation.ConstraintViolationException;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@link TranscriptionService} over {@link SpeechToTextClient} (#2074).
 *
 * <p>Transcribe-and-discard: the audio bytes read here live only for the duration of this call —
 * they are never persisted and never logged. Only size, MIME type, requested/effective language,
 * clip duration, and call latency are logged.
 */
@Service
public class TranscriptionServiceImpl implements TranscriptionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TranscriptionServiceImpl.class);

    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of("audio/webm", "audio/ogg", "audio/mp4");

    private final SpeechToTextClient client;
    private final TranscriptionProperties properties;

    public TranscriptionServiceImpl(@NonNull SpeechToTextClient client, @NonNull TranscriptionProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public @NonNull TranscriptionResponse transcribe(
            @Nullable MultipartFile audio, @Nullable String language, @NonNull Locale requestLocale) {
        if (audio == null || audio.isEmpty()) {
            throw new UnsupportedAudioException("audio is required and must not be empty");
        }
        long maxBytes = properties.maxBytes().toBytes();
        if (audio.getSize() > maxBytes) {
            throw new AudioTooLargeException("audio exceeds the maximum size of " + maxBytes + " bytes");
        }
        String baseMimeType = baseMimeType(audio.getContentType());
        if (baseMimeType == null || !SUPPORTED_MIME_TYPES.contains(baseMimeType)) {
            throw new UnsupportedAudioException("audio content type must be one of " + SUPPORTED_MIME_TYPES);
        }
        validateLanguageTag(language);
        String effectiveLanguage = effectiveLanguage(language, requestLocale);

        byte[] bytes;
        try {
            bytes = audio.getBytes();
        } catch (java.io.IOException e) {
            throw new UnsupportedAudioException("audio could not be read", e);
        }
        String filename = filename(audio.getOriginalFilename(), baseMimeType);

        long start = System.nanoTime();
        SpeechToTextResult result = client.transcribe(bytes, filename, baseMimeType, effectiveLanguage);
        long latencyMillis = (System.nanoTime() - start) / 1_000_000;

        String text = result.text() == null ? "" : result.text().trim();
        if (text.isBlank()) {
            throw new UnintelligibleAudioException("transcription produced no usable text");
        }
        Double durationSeconds = result.durationSeconds();
        if (durationSeconds != null && durationSeconds > properties.maxDurationSeconds()) {
            throw new AudioTooLargeException("audio duration " + durationSeconds + "s exceeds the maximum of "
                    + properties.maxDurationSeconds() + "s");
        }
        String responseLanguage = firstNonBlank(result.language(), effectiveLanguage, "und");

        LOGGER.info(
                "transcription completed: sizeBytes={}, mimeType={}, language={}, durationSeconds={}, latencyMs={}",
                bytes.length,
                baseMimeType,
                responseLanguage,
                durationSeconds,
                latencyMillis);

        return new TranscriptionResponse(text, responseLanguage, durationSeconds);
    }

    /** Validates {@code language} is well-formed BCP-47 when present; 400 via the module's validation path. */
    private static void validateLanguageTag(@Nullable String language) {
        if (language == null || language.isBlank()) {
            return;
        }
        try {
            new Locale.Builder().setLanguageTag(language).build();
        } catch (IllformedLocaleException e) {
            throw new ConstraintViolationException("language must be a well-formed BCP-47 tag", Set.of());
        }
    }

    /**
     * Effective language sent to the provider: the requested tag, else the request locale's tag
     * (unless it is undetermined), else {@code null} to let the provider auto-detect.
     */
    private static @Nullable String effectiveLanguage(@Nullable String language, @NonNull Locale requestLocale) {
        if (language != null && !language.isBlank()) {
            return language;
        }
        String localeTag = requestLocale.toLanguageTag();
        return "und".equals(localeTag) ? null : localeTag;
    }

    /** The MIME type before any {@code ;parameter}, lower-cased and trimmed; {@code null} when absent. */
    private static @Nullable String baseMimeType(@Nullable String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        int separator = contentType.indexOf(';');
        String base = separator >= 0 ? contentType.substring(0, separator) : contentType;
        return base.strip().toLowerCase(Locale.ROOT);
    }

    /** {@code originalFilename} when present, else {@code clip.<ext>} derived from {@code baseMimeType}. */
    private static @NonNull String filename(@Nullable String originalFilename, @NonNull String baseMimeType) {
        if (originalFilename != null && !originalFilename.isBlank()) {
            return originalFilename;
        }
        String extension =
                switch (baseMimeType) {
                    case "audio/webm" -> "webm";
                    case "audio/ogg" -> "ogg";
                    case "audio/mp4" -> "mp4";
                    default -> "bin";
                };
        return "clip." + extension;
    }

    private static @NonNull String firstNonBlank(@Nullable String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        throw new IllegalStateException("no non-blank candidate");
    }
}
