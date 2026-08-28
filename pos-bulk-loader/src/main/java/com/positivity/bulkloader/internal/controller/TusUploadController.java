package com.positivity.bulkloader.internal.controller;

import com.positivity.bulkloader.internal.security.BulkImportPermissions;
import com.positivity.bulkloader.internal.service.TusUploadService;
import com.positivity.events.EmitEvent;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"bulkImport:upload:execute", "permitAll"})
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
    @PreAuthorize("permitAll()")
    @Operation(operationId = "getTusCapabilities", summary = "Get TUS Server Capabilities", description = """
                    Advertises the TUS resumable-upload capabilities of this server, namely protocol version 1.0.0, \
                    the creation, termination and expiration extensions, and the maximum upload size.
                    Use this tool during the tus client handshake to discover limits before creating an upload; do \
                    not use it to check the progress of an existing upload, which is getTusUploadOffset.
                    Preconditions: none; this endpoint requires no authentication.
                    Required inputs: none; there are no parameters and no request body.
                    No events are emitted and no state changes; the capabilities are returned in the Tus-Version, \
                    Tus-Max-Size and Tus-Extension response headers.
                    Returns 204 in all cases, with the capability data carried in headers rather than a body.
                    """)
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
    @PreAuthorize("hasAuthority('" + BulkImportPermissions.UPLOAD_EXECUTE + "')")
    @EmitEvent(id = "BULK_LOADER_TUS_UPLOAD_CREATE", apiVersion = "1")
    @Operation(operationId = "createTusUpload", summary = "Create a Resumable Upload", description = """
                    Creates a resumable TUS upload session scoped to a bulk load job and returns its absolute upload \
                    URL in the Location header, rebuilt from the gateway's X-Forwarded headers so it reflects the \
                    public address.
                    Use this tool to start uploading a large file in chunks; use uploadJobFile instead when the \
                    file is small enough for a single multipart request, and do not send file bytes here because \
                    chunks go to appendTusUploadChunk.
                    Preconditions: the caller must send a Tus-Resumable header of 1.0.0; the job id is recorded but \
                    not validated here, so a wrong job id only fails later when the finished file is attached to \
                    the job.
                    Required inputs: Upload-Length header with the total file size in bytes (server maximum \
                    536870912 by default), and optionally Upload-Metadata with a base64-encoded filename field, \
                    without which the file is stored as upload.bin.
                    Emits a BULK_LOADER_TUS_UPLOAD_CREATE event and creates an empty temp file; the session expires \
                    after 24 hours by default (see the Upload-Expires header) and expired incomplete uploads are \
                    cleaned up automatically.
                    Returns 201 with Location, Upload-Offset and Upload-Expires headers, 412 when the Tus-Resumable \
                    header is missing or not 1.0.0, and 413 when Upload-Length exceeds the server maximum.
                    """)
    @ApiResponse(responseCode = "201", description = "Upload created")
    @ApiResponse(responseCode = "412", description = "Unsupported TUS version")
    @ApiResponse(responseCode = "413", description = "Upload-Length exceeds server maximum")
    public ResponseEntity<Void> createUpload(
            @PathVariable @NonNull UUID jobId,
            @RequestHeader(value = TUS_RESUMABLE, required = false) @Nullable String tusResumable,
            @RequestHeader("Upload-Length") long uploadLength,
            @RequestHeader(value = "Upload-Metadata", required = false) @Nullable String uploadMetadata) {

        ResponseEntity<Void> versionError = rejectIfUnsupportedVersion(tusResumable);
        if (versionError != null) return versionError;

        if (uploadLength > maxUploadSize) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .header(TUS_RESUMABLE, TUS_VERSION)
                    .build();
        }

        String fileName = parseFilename(uploadMetadata);
        TusUploadService.Created created =
                tusUploadService.createUpload(jobId, fileName, uploadLength, resolveOperatorId());

        // Absolute URL, as in the tus spec's own examples: relative Location references break
        // tus clients whose endpoint is a bare path, and stale copies of them resolve against
        // the page URL instead of the API (see issue #833). ForwardedHeaderFilter
        // (server.forward-headers-strategy: framework) has already rebuilt this request from the
        // X-Forwarded-Proto/Host/Port/Prefix headers set by the gateway/proxy chain, so the
        // base below is the public address (e.g. https://host/bulk-loader), not the internal
        // one with the gateway prefix stripped.
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/v1/tus/{uploadId}")
                .build(created.id());
        return ResponseEntity.created(location)
                .header(TUS_RESUMABLE, TUS_VERSION)
                .header(UPLOAD_OFFSET, "0")
                .header("Upload-Expires", formatRfc1123(created.expiresAt()))
                .build();
    }

    @RequestMapping(value = "/tus/{uploadId}", method = RequestMethod.HEAD)
    @PreAuthorize("hasAuthority('" + BulkImportPermissions.UPLOAD_EXECUTE + "')")
    @Operation(operationId = "getTusUploadOffset", summary = "Get Current Upload Offset", description = """
                    Returns the current byte offset of a TUS upload in the Upload-Offset response header so a client \
                    can resume where the last transfer stopped.
                    Use this tool after an interrupted transfer to learn where to resume; do not use \
                    getTusCapabilities, which reports server-wide limits rather than per-upload progress.
                    Preconditions: the upload session must exist and the caller must send a Tus-Resumable header \
                    of 1.0.0.
                    Required inputs: uploadId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; the response carries Upload-Offset, Upload-Length \
                    and Upload-Expires headers with an empty body.
                    Returns 404 when the upload does not exist, and 412 when the Tus-Resumable header is missing \
                    or not 1.0.0.
                    """)
    @ApiResponse(responseCode = "200", description = "Current upload offset")
    @ApiResponse(responseCode = "404", description = "Upload not found")
    @ApiResponse(responseCode = "412", description = "Unsupported TUS version")
    public ResponseEntity<Void> getOffset(
            @PathVariable @NonNull UUID uploadId,
            @RequestHeader(value = TUS_RESUMABLE, required = false) @Nullable String tusResumable) {

        ResponseEntity<Void> versionError = rejectIfUnsupportedVersion(tusResumable);
        if (versionError != null) return versionError;

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
    @PreAuthorize("hasAuthority('" + BulkImportPermissions.UPLOAD_EXECUTE + "')")
    @EmitEvent(id = "BULK_LOADER_TUS_UPLOAD_CHUNK_APPEND", apiVersion = "1")
    @Operation(operationId = "appendTusUploadChunk", summary = "Upload a Chunk", description = """
                    Appends a contiguous byte range to an in-progress TUS upload and, when the final byte arrives, \
                    moves the finished file into job storage.
                    Use this tool repeatedly to stream the file in chunks after createTusUpload; do not guess the \
                    offset after a failure, and call getTusUploadOffset instead to learn the server-side offset.
                    Preconditions: the upload must exist, not be complete, and not be expired; the Upload-Offset \
                    header must exactly equal the server's current offset.
                    Required inputs: uploadId (UUID) as a path parameter, a Content-Type of \
                    application/offset+octet-stream, Tus-Resumable, Upload-Offset and Content-Length headers, and \
                    the raw chunk bytes as the request body.
                    Emits a BULK_LOADER_TUS_UPLOAD_CHUNK_APPEND event; when the new offset reaches Upload-Length \
                    the file is finalized into the job's storage and the job records the upload, moving a CREATED \
                    job to UPLOADING (finalization fails with 404 when the job is gone or 409 when it is terminal).
                    Returns 204 with the new Upload-Offset header on success, 409 when the offset does not match or \
                    the upload is already complete, 410 when the upload has expired, 415 when the Content-Type is \
                    wrong, 412 when the TUS version is unsupported, and 404 when the upload does not exist.
                    """)
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
            HttpServletRequest request)
            throws IOException {

        ResponseEntity<Void> versionError = rejectIfUnsupportedVersion(tusResumable);
        if (versionError != null) return versionError;

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
    @PreAuthorize("hasAuthority('" + BulkImportPermissions.UPLOAD_EXECUTE + "')")
    @EmitEvent(id = "BULK_LOADER_TUS_UPLOAD_CANCEL", apiVersion = "1")
    @Operation(operationId = "cancelTusUpload", summary = "Cancel a Resumable Upload", description = """
                    Cancels a TUS upload session, permanently deleting both the session record and its temporary \
                    chunk file.
                    Use this tool to abandon a partially transferred upload; do not use cancelBulkLoadJob, which \
                    cancels the bulk load job itself rather than an upload session.
                    Preconditions: the upload session must exist and the caller must send a Tus-Resumable header of \
                    1.0.0; a completed upload whose file has already been attached to the job is not detached by \
                    this call.
                    Required inputs: uploadId (UUID) as a path parameter; there is no request body.
                    Emits a BULK_LOADER_TUS_UPLOAD_CANCEL event and removes the temporary file; the deletion cannot \
                    be undone and the upload URL becomes invalid.
                    Returns 204 on success, 404 when the upload does not exist, and 412 when the Tus-Resumable \
                    header is missing or not 1.0.0.
                    """)
    @ApiResponse(responseCode = "204", description = "Upload deleted")
    @ApiResponse(responseCode = "404", description = "Upload not found")
    @ApiResponse(responseCode = "412", description = "Unsupported TUS version")
    public ResponseEntity<Void> deleteUpload(
            @PathVariable @NonNull UUID uploadId,
            @RequestHeader(value = TUS_RESUMABLE, required = false) @Nullable String tusResumable) {

        ResponseEntity<Void> versionError = rejectIfUnsupportedVersion(tusResumable);
        if (versionError != null) return versionError;

        tusUploadService.deleteUpload(uploadId);
        return ResponseEntity.noContent().header(TUS_RESUMABLE, TUS_VERSION).build();
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
        if (metadata == null || metadata.isBlank()) return "upload.bin";
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
