package com.positivity.mcp.internal.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * #2074: speech-to-text provider and clip limits ({@code mcp.transcription.*}).
 *
 * <p>The provider is any OpenAI-compatible {@code /audio/transcriptions} endpoint: OpenAI itself or a
 * self-hosted faster-whisper / speaches server. Transcription is off until {@code base-url} is set;
 * while it is off the endpoint answers 503 {@code TRANSCRIPTION_UNAVAILABLE}.
 *
 * @param baseUrl provider base URL including the API version segment (e.g. {@code
 *     https://api.openai.com/v1}, {@code http://speaches:8000/v1}); blank or absent disables
 *     transcription
 * @param apiKey bearer token for the provider; blank sends no {@code Authorization} header (for
 *     self-hosted servers without auth). Never logged.
 * @param model provider model id; the model must support the {@code verbose_json} response format
 *     (the provider-reported clip duration comes from it)
 * @param timeout whole-call timeout for one transcription request (connect, upload, and response)
 * @param maxBytes largest accepted clip, inclusive ({@code 5MB} is 5 MiB)
 * @param maxDurationSeconds longest accepted clip in seconds, inclusive, checked against the
 *     provider-reported duration
 */
@Validated
@ConfigurationProperties(prefix = "mcp.transcription")
public record TranscriptionProperties(
        @Nullable String baseUrl,
        @Nullable String apiKey,
        @DefaultValue("whisper-1") @NotBlank String model,
        @DefaultValue("30s") @NotNull Duration timeout,
        @DefaultValue("5MB") @NotNull DataSize maxBytes,
        @DefaultValue("60") @Min(1) int maxDurationSeconds) {

    /** True when a provider base URL is set, i.e. transcription is enabled. */
    public boolean isEnabled() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    /** Redacts the API key so a logged or printed properties object never leaks it. */
    @Override
    public String toString() {
        return "TranscriptionProperties[baseUrl=" + baseUrl
                + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<unset>" : "***")
                + ", model=" + model
                + ", timeout=" + timeout
                + ", maxBytes=" + maxBytes
                + ", maxDurationSeconds=" + maxDurationSeconds
                + "]";
    }
}
