package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.dto.TranscriptionResponse;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.web.multipart.MultipartFile;

/**
 * Transcribes one audio clip to text (#2074).
 *
 * <p>Transcribe-and-discard: an implementation must hold the audio bytes in memory for the
 * duration of the call only, never persist them, and never log their content (size/type/duration
 * are fine to log; the bytes themselves are not).
 */
public interface TranscriptionService {

    /**
     * Transcribes {@code audio} to text.
     *
     * @param audio the recorded clip; {@code null} or empty is rejected as unsupported (415), not
     *     treated as "no audio to transcribe"
     * @param language optional BCP-47 tag requested by the caller; when absent, {@code
     *     requestLocale} is used as a fallback before falling back to provider auto-detection
     * @param requestLocale the locale Spring resolved for this request (from {@code
     *     Accept-Language}), passed through so the service can fall back to it when {@code
     *     language} is absent
     * @return the transcript, never blank
     */
    @NonNull
    TranscriptionResponse transcribe(
            @Nullable MultipartFile audio, @Nullable String language, @NonNull Locale requestLocale);
}
