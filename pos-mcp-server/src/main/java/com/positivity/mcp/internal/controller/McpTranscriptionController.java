package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.dto.TranscriptionResponse;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.internal.service.TranscriptionService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Server-side speech-to-text fallback for the assistant composer (#2074), for browsers where the
 * in-browser {@code SpeechRecognition}/{@code webkitSpeechRecognition} API is unavailable
 * (notably Firefox).
 *
 * <p><strong>Retention: transcribe-and-discard.</strong> The uploaded audio is held in memory for
 * the lifetime of this request only. It is never written to disk, never persisted to a database,
 * and never included in logs or in the {@code MCP_TRANSCRIPTION_EXECUTE} event this endpoint
 * emits ({@code @EmitEvent} carries only an event id/API version/timing, never method arguments —
 * no audio reaches the event). The clip is forwarded to the configured speech-to-text provider to
 * produce the transcript and is discarded once this response is built. If the configured provider
 * is a hosted third party (for example OpenAI), that provider's own retention policy applies to
 * the copy it received. If it is self-hosted — infrastructure under our own control — it keeps
 * nothing beyond serving this one request.
 *
 * <p>Reuses {@code mcp:chat:execute} rather than a new permission — voice is an input modality of
 * chat, and minting a new permission would need bitset/gateway/role-seed work this story does not
 * include (every caller would 403 until that lands, mirroring the reasoning in {@link
 * McpConversationController}).
 */
@RestController
@RequestMapping("/v1/mcp")
@Tag(name = "MCP Transcription", description = "Server-side audio transcription fallback for the assistant composer")
class McpTranscriptionController {

    static final String PATH = "/v1/mcp/transcriptions";

    private final TranscriptionService transcriptionService;

    McpTranscriptionController(@NonNull TranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    @PostMapping(value = "/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(operationId = "transcribeMcpAudio", summary = "Transcribe an Audio Clip", description = """
                    Transcribes one short audio clip to text, for browsers where the in-browser \
                    SpeechRecognition API is unavailable.
                    Preconditions: the clip is at most 5 MiB and at most 60 seconds long (both limits \
                    inclusive); its content type (ignoring parameters such as `;codecs=opus`) must be one of \
                    audio/webm, audio/ogg, audio/mp4.
                    Required inputs: audio (the recorded clip, multipart file part, required) and language \
                    (optional BCP-47 tag, e.g. "en-US"); when language is omitted the request's resolved \
                    Accept-Language locale is used, falling back to provider auto-detection when that locale \
                    is undetermined.
                    Retention: transcribe-and-discard — the audio is held in memory for this request only, \
                    forwarded to the configured speech-to-text provider, and never persisted, logged, or \
                    included in the emitted event. A hosted provider's own retention policy applies to the \
                    copy it received. A self-hosted provider — infrastructure under our own control — keeps \
                    nothing beyond serving this one request.
                    Emits an MCP_TRANSCRIPTION_EXECUTE event.
                    Returns 200 with the transcript synchronously — clips this short never queue for later \
                    polling.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Transcript returned",
            content = @Content(schema = @Schema(implementation = TranscriptionResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "language is not a well-formed BCP-47 tag, or the multipart request body is "
                    + "malformed/truncated (a bad boundary, or the client aborting mid-upload)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Authentication is required",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks mcp:chat:execute",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "413",
            description = "Clip over 5 MiB, or provider-reported duration over 60 seconds",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "415",
            description = "Request is not multipart, the audio part is missing/empty, or its content type is "
                    + "outside audio/webm, audio/ogg, audio/mp4",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Nothing intelligible in the clip (blank transcript), or the provider rejected it "
                    + "as undecodable",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "Speech-to-text provider not configured, unreachable, or erroring — never a bare 500",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:chat:execute"})
    @PreAuthorize("hasAuthority('" + McpPermissions.MCP_CHAT_EXECUTE + "')")
    @EmitEvent(id = "MCP_TRANSCRIPTION_EXECUTE", apiVersion = "1")
    ResponseEntity<TranscriptionResponse> transcribe(
            @Parameter(
                            name = "audio",
                            description = "The recorded clip. At most 5 MiB, at most 60 seconds. Content type "
                                    + "(ignoring `;codecs=` parameters) must be audio/webm, audio/ogg, or "
                                    + "audio/mp4.",
                            required = true,
                            content = {
                                @Content(
                                        mediaType = "audio/webm",
                                        schema = @Schema(type = "string", format = "binary")),
                                @Content(mediaType = "audio/ogg", schema = @Schema(type = "string", format = "binary")),
                                @Content(mediaType = "audio/mp4", schema = @Schema(type = "string", format = "binary"))
                            })
                    @RequestPart("audio")
                    MultipartFile audio,
            @Parameter(
                            name = "language",
                            description = "Optional BCP-47 language tag, e.g. \"en-US\". Falls back to the "
                                    + "request's resolved locale, then to provider auto-detection.",
                            required = false,
                            schema = @Schema(type = "string", example = "en-US"))
                    @RequestPart(value = "language", required = false)
                    @Nullable
                    String language,
            @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) @Nullable
                    String acceptLanguage) {
        TranscriptionResponse response =
                transcriptionService.transcribe(audio, language, resolveLocale(acceptLanguage));
        return ResponseEntity.ok(response);
    }

    /**
     * The request's resolved locale, from the raw {@code Accept-Language} header only — no
     * container/server default fallback. Spring's implicit {@code Locale} argument resolution
     * (previously used here) delegates to {@code HttpServletRequest#getLocale()}, which falls back
     * to the container's default locale (e.g. the JVM default) when the header is absent, not to
     * an undetermined locale; that silently defeated {@link TranscriptionService}'s auto-detect
     * fallback for every caller that omits the header. {@link Locale#ROOT} ({@code "und"}) is
     * returned instead, so a missing header reaches the service as "undetermined" rather than as
     * an arbitrary guessed language.
     */
    private static @NonNull Locale resolveLocale(@Nullable String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return Locale.ROOT;
        }
        List<Locale.LanguageRange> ranges;
        try {
            ranges = Locale.LanguageRange.parse(acceptLanguage);
        } catch (IllegalArgumentException e) {
            return Locale.ROOT;
        }
        return ranges.isEmpty()
                ? Locale.ROOT
                : Locale.forLanguageTag(ranges.get(0).getRange());
    }
}
