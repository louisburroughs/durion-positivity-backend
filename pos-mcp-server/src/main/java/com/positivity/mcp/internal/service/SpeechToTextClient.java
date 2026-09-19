package com.positivity.mcp.internal.service;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * #2074: outbound speech-to-text call. The audio is sent to the configured provider and dropped when
 * the call returns; implementations hold no reference to it and never log it.
 */
public interface SpeechToTextClient {

    /** False when no provider is configured; {@link #transcribe} then always fails with 503. */
    boolean isConfigured();

    /**
     * Transcribes one clip.
     *
     * @param audio the clip bytes, already size-checked by the caller
     * @param filename a name for the multipart file part; providers infer the container from its
     *     extension, so an unusable name is replaced with one derived from {@code mimeType}
     * @param mimeType the clip's content type (parameters such as {@code ;codecs=opus} allowed)
     * @param language optional BCP-47 tag; only its primary language subtag is sent to the provider,
     *     null lets the provider detect the language
     * @return the transcript (never null, possibly blank), the language (BCP-47, or null when
     *     neither the caller nor the provider named one), and the provider-reported duration in
     *     seconds (null when the provider did not report one)
     * @throws com.positivity.mcp.internal.exception.TranscriptionUnavailableException no provider
     *     configured, provider unreachable or timed out, or provider answered 401/403/429/5xx or
     *     another non-400 error
     * @throws com.positivity.mcp.internal.exception.UnintelligibleAudioException the provider
     *     rejected the clip itself (HTTP 400)
     */
    @NonNull
    SpeechToTextResult transcribe(
            byte @NonNull [] audio, @NonNull String filename, @NonNull String mimeType, @Nullable String language);

    /**
     * @param text the transcript, never null (possibly blank)
     * @param language BCP-47 language tag, or null when unknown
     * @param durationSeconds provider-reported clip length, or null when not reported
     */
    record SpeechToTextResult(
            @NonNull String text,
            @Nullable String language,
            @Nullable Double durationSeconds) {}
}
