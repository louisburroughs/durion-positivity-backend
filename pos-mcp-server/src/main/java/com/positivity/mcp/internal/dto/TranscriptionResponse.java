package com.positivity.mcp.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Result of transcribing one audio clip (#2074).
 *
 * <p>Transcribe-and-discard: the audio bytes that produced this response were never persisted and
 * never logged — they were held in memory for the duration of this request only, forwarded to the
 * configured speech-to-text provider, and discarded once this response was built. See {@link
 * com.positivity.mcp.internal.controller.McpTranscriptionController} for the full retention
 * statement.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
        name = "TranscriptionResponse",
        description = "Transcript produced from one audio clip. The audio itself is never returned or stored.",
        requiredProperties = {"text", "language"})
public record TranscriptionResponse(
        @Schema(
                description = "The transcribed text, trimmed. Never blank — a clip with nothing intelligible in "
                        + "it fails the request (422) rather than returning an empty transcript.",
                example = "how many mechanics do i have")
        @NonNull
        String text,

        @Schema(
                description = "BCP-47 language tag for the transcript: the requested `language`, else the "
                        + "provider-detected language, else \"und\" (undetermined) when neither is known.",
                example = "en-US")
        @NonNull
        String language,

        @Schema(
                description = "Clip duration in seconds as reported by the speech-to-text provider. Omitted when "
                        + "the provider does not report it — length is then bounded only by the request's size "
                        + "limit, not returned to the caller.",
                example = "7.2")
        @Nullable
        Double durationSeconds) {}
