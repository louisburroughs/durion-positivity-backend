package com.positivity.bulkloader.internal.controller;

import com.positivity.bulkloader.service.TusUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@Slf4j
@Tag(name = "TUS Resumable Upload API", description = "Resumable file uploads following the tus.io 1.0.0 protocol")
public class TusUploadController {

    private static final String TUS_RESUMABLE = "Tus-Resumable";
    private static final String UPLOAD_OFFSET = "Upload-Offset";
    private static final String TUS_VERSION = "1.0.0";
    private static final String TUS_EXTENSIONS = "creation,termination,expiration";

    private final TusUploadService tusUploadService;
    private final long maxUploadSize;

    public TusUploadController(
            TusUploadService tusUploadService,
            @Value("${bulk-loader.tus.max-upload-size:536870912}") long maxUploadSize) {
        this.tusUploadService = tusUploadService;
        this.maxUploadSize = maxUploadSize;
    }

    @RequestMapping(value = "/tus", method = RequestMethod.OPTIONS)
    @Operation(summary = "TUS server capabilities", description = "Returns supported TUS version, extensions, and max upload size. No authentication required.")
    @ApiResponse(responseCode = "204", description = "Server capabilities")
    public ResponseEntity<Void> options() {
        return ResponseEntity.noContent()
                .header(TUS_RESUMABLE, TUS_VERSION)
                .header("Tus-Version", TUS_VERSION)
                .header("Tus-Max-Size", String.valueOf(maxUploadSize))
                .header("Tus-Extension", TUS_EXTENSIONS)
                .build();
    }

    @PostMapping("/bulk-jobs/{jobId}/tus")
    @PreAuthorize("hasAuthority('bulkImport:upload:execute')")
    @Operation(summary = "Create a resumable upload", description = "Creates a new TUS upload scoped to a bulk load job. "
            + "Returns a Location header with the upload URL for subsequent HEAD and PATCH requests.")
    @ApiResponse(responseCode = "201", description = "Upload created")
    @ApiResponse(responseCode = "412", description = "Unsupported TUS version")
    @ApiResponse(responseCode = "413", description = "Upload-Length exceeds server maximum")
    public ResponseEntity<Void> createUpload(
            @PathVariable @NonNull UUID jobId,
            @RequestHeader(value = TUS_RESUMABLE, required = false) @Nullable String tusResumable,
            @RequestHeader("Upload-Length") long uploadLength,
            @RequestHeader(value = "Upload-Metadata", required = false) @Nullable String uploadMetadata,
            HttpServletRequest request) {

        ResponseEntity<Void> versionError = rejectIfUnsupportedVersion(tusResumable);
        if (versionError != null)
            return versionError;

        if (uploadLength > maxUploadSize) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .header(TUS_RESUMABLE, TUS_VERSION)
                    .build();
        }

        String fileName = parseFilename(uploadMetadata);
        TusUploadService.Created created = tusUploadService.createUpload(jobId, fileName, uploadLength,
                resolveOperatorId());

        String location = resolveBaseUrl(request) + "/v1/tus/" + created.id();
        return ResponseEntity.created(URI.create(location))
                .header(TUS_RESUMABLE, TUS_VERSION)
                .header(UPLOAD_OFFSET, "0")
                .header("Upload-Expires", formatRfc1123(created.expiresAt()))
                .build();
    }

    @RequestMapping(value = "/tus/{uploadId}", method = RequestMethod.HEAD)
    @PreAuthorize("hasAuthority('bulkImport:upload:execute')")
    @Operation(summary = "Get upload offset", description = "Returns the current byte offset of a TUS upload for resumption.")
    @ApiResponse(responseCode = "200", description = "Current upload offset")
    @ApiResponse(responseCode = "404", description = "Upload not found")
    @ApiResponse(responseCode = "412", description = "Unsupported TUS version")
    public ResponseEntity<Void> getOffset(
            @PathVariable @NonNull UUID uploadId,
            @RequestHeader(value = TUS_RESUMABLE, required = false) @Nullable String tusResumable) {

        ResponseEntity<Void> versionError = rejectIfUnsupportedVersion(tusResumable);
        if (versionError != null)
            return versionError;

        TusUploadService.Info info = tusUploadService.getInfo(uploadId);
        return ResponseEntity.ok()
                .header(TUS_RESUMABLE, TUS_VERSION)
                .header(UPLOAD_OFFSET, String.valueOf(info.uploadOffset()))
                .header("Upload-Length", String.valueOf(info.totalSize()))
                .header("Upload-Expires", formatRfc1123(info.expiresAt()))
                .header("Cache-Control", "no-store")
                .build();
    }

    @PatchMapping("/tus/{uploadId}")
    @PreAuthorize("hasAuthority('bulkImport:upload:execute')")
    @Operation(summary = "Upload a chunk", description = "Appends a byte range to an in-progress TUS upload. "
            + "Content-Type must be application/offset+octet-stream. "
            + "Upload-Offset must equal the current server-side offset.")
    @ApiResponse(responseCode = "204", description = "Chunk accepted, new offset returned")
    @ApiResponse(responseCode = "409", description = "Upload-Offset conflict")
    @ApiResponse(responseCode = "410", description = "Upload has expired")
    @ApiResponse(responseCode = "412", description = "Unsupported TUS version")
    @ApiResponse(responseCode = "415", description = "Content-Type must be application/offset+octet-stream")
    public ResponseEntity<Void> uploadChunk(
            @PathVariable @NonNull UUID uploadId,
            @RequestHeader(value = TUS_RESUMABLE, required = false) @Nullable String tusResumable,
            @RequestHeader(UPLOAD_OFFSET) long uploadOffset,
            @RequestHeader("Content-Length") long contentLength,
            @RequestHeader(value = "Content-Type", required = false) @Nullable String contentType,
            HttpServletRequest request) throws IOException {

        ResponseEntity<Void> versionError = rejectIfUnsupportedVersion(tusResumable);
        if (versionError != null)
            return versionError;

        if (!"application/offset+octet-stream".equals(contentType)) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .header(TUS_RESUMABLE, TUS_VERSION)
                    .build();
        }

        long newOffset = tusUploadService.appendChunk(uploadId, uploadOffset, request.getInputStream(), contentLength);
        return ResponseEntity.noContent()
                .header(TUS_RESUMABLE, TUS_VERSION)
                .header(UPLOAD_OFFSET, String.valueOf(newOffset))
                .build();
    }

    @DeleteMapping("/tus/{uploadId}")
    @PreAuthorize("hasAuthority('bulkImport:upload:execute')")
    @Operation(summary = "Cancel an upload", description = "Permanently deletes a TUS upload and its temporary file.")
    @ApiResponse(responseCode = "204", description = "Upload deleted")
    @ApiResponse(responseCode = "404", description = "Upload not found")
    @ApiResponse(responseCode = "412", description = "Unsupported TUS version")
    public ResponseEntity<Void> deleteUpload(
            @PathVariable @NonNull UUID uploadId,
            @RequestHeader(value = TUS_RESUMABLE, required = false) @Nullable String tusResumable) {

        ResponseEntity<Void> versionError = rejectIfUnsupportedVersion(tusResumable);
        if (versionError != null)
            return versionError;

        tusUploadService.deleteUpload(uploadId);
        return ResponseEntity.noContent()
                .header(TUS_RESUMABLE, TUS_VERSION)
                .build();
    }

    private @Nullable ResponseEntity<Void> rejectIfUnsupportedVersion(@Nullable String tusResumable) {
        if (!TUS_VERSION.equals(tusResumable)) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                    .header(TUS_RESUMABLE, TUS_VERSION)
                    .header("Tus-Version", TUS_VERSION)
                    .build();
        }
        return null;
    }

    private String parseFilename(@Nullable String metadata) {
        if (metadata == null || metadata.isBlank())
            return "upload.bin";
        for (String entry : metadata.split(",")) {
            String[] kv = entry.trim().split(" ", 2);
            if (kv.length == 2 && "filename".equalsIgnoreCase(kv[0])) {
                try {
                    return new String(Base64.getDecoder().decode(kv[1].trim()), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException _) {
                    log.warn("Invalid base64 in Upload-Metadata filename field: {}", kv[1]);
                }
            }
        }
        return "upload.bin";
    }

    private String resolveBaseUrl(HttpServletRequest request) {
        String url = request.getRequestURL().toString();
        int idx = url.indexOf("/v1/");
        return idx >= 0 ? url.substring(0, idx) : "";
    }

    private String formatRfc1123(Instant instant) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(instant.atZone(ZoneOffset.UTC));
    }

    private String resolveOperatorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            throw new IllegalStateException("Authenticated operator is required");
        }
        return authentication.getName();
    }
}
