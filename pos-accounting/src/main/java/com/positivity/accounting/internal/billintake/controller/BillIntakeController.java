package com.positivity.accounting.internal.billintake.controller;

import com.positivity.accounting.internal.billintake.dto.BillIntakeUploadResponse;
import com.positivity.accounting.internal.billintake.enums.BillIntakeChannel;
import com.positivity.accounting.internal.billintake.service.BillIntakeEntry;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Bill intake (CAP:550 S25, #2518; SPEC-accounting-workspace 4.8): upload documents as bill drafts. Part B of the story
 * ships the upload; listing, editing, confirming and discarding drafts follow in Part C.
 */
@RestController
@RequestMapping("/v1/accounting/bill-intake")
@RequiredArgsConstructor
@Tag(name = "Bill Intake", description = "Upload documents as bill drafts for a person to check before a bill exists")
public class BillIntakeController {

    private static final String SYSTEM_USER = "SYSTEM";

    private final BillIntakeEntry entry;

    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.BILL_INTAKE_CREATE})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.BILL_INTAKE_CREATE + "')")
    @EmitEvent(id = "ACCOUNTING_BILL_INTAKE_UPLOAD", apiVersion = "1")
    @Operation(
            operationId = "uploadBillDocuments",
            summary = "Upload Bill Documents",
            description = """
                Uploads up to 10 documents (PDF, JPEG, PNG, HEIC or TIFF, each at most 25 MB, 100 MB in all) and creates \
                one bill draft per file. Each file is stored apart and waits to be scanned for malware and read; a person \
                checks the draft before any bill exists, and nothing posts.
                Use this tool to bring in a vendor's bill that arrived as a document; do not use it for a spreadsheet of \
                bills (an import), for a bank statement (createBankImport), or for a goods receipt \
                (createVendorBillFromGoodsReceived).
                Preconditions: the caller holds accounting:bill_intake:create; the file's type is judged by its content, \
                never by its name or declared type, and a file of any other type is refused at once with status \
                FILE_REJECTED and reason TYPE_NOT_ALLOWED.
                Required inputs: files (1 to 10 parts) and a UUIDv7 requestId; a replay of the same requestId with the \
                same files returns the same drafts.
                Emits an ACCOUNTING_BILL_INTAKE_UPLOAD event and one BILL_INTAKE audit row per file naming its sha-256.
                Returns 201 with one item per file (QUARANTINED, or FILE_REJECTED with a reason); an outage of the scanner \
                or the reader never rejects a file, it stays QUARANTINED. 400 when no file or more than 10 are sent, \
                409 IDEMPOTENCY_CONFLICT when the requestId was used with other files, 413 PAYLOAD_TOO_LARGE over the \
                size limits, 403 without the permission.
                """,
            tags = {"Bill Intake"})
    @ApiResponse(
            responseCode = "201",
            description = "One draft per file",
            content = @Content(schema = @Schema(implementation = BillIntakeUploadResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: no file, or more than 10",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:bill_intake:create",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "IDEMPOTENCY_CONFLICT: the requestId was used with other files",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "413",
            description = "PAYLOAD_TOO_LARGE: a file over 25 MB or a request over 100 MB",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BillIntakeUploadResponse> uploadBillDocuments(
            @Parameter(description = "The documents, 1 to 10 parts named files") @RequestPart("files") @NonNull
                    List<MultipartFile> files,
            @Parameter(description = "A caller-generated UUIDv7 that makes the upload replayable")
                    @RequestParam("requestId")
                    @NonNull
                    UUID requestId) {
        List<BillIntakeEntry.IncomingFile> incoming = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                incoming.add(new BillIntakeEntry.IncomingFile(
                        file.getBytes(), file.getContentType(), file.getOriginalFilename()));
            } catch (IOException unreadable) {
                throw new VendorBillException(
                        VendorBillException.Code.VALIDATION_ERROR, "An uploaded file could not be read");
            }
        }
        BillIntakeEntry.Received received = entry.receive(
                incoming,
                BillIntakeChannel.UPLOAD,
                requestId,
                SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_USER));
        return ResponseEntity.status(HttpStatus.CREATED).body(new BillIntakeUploadResponse(received.items()));
    }
}
