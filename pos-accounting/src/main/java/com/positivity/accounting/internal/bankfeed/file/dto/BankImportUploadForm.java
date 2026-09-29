package com.positivity.accounting.internal.bankfeed.file.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * The multipart form of an upload (SPEC §4.3, §6.1; story S3, #2302), for the OpenAPI document only:
 * the {@code file} part carries the bytes and the {@code meta} part (application/json) the request
 * without {@code content}.
 */
@Getter
@Schema(description = "Multipart upload: the statement file and the request as a JSON part")
public final class BankImportUploadForm {

    @Schema(description = "The statement file", type = "string", format = "binary", requiredMode = REQUIRED)
    private String file;

    @Schema(
            description = "The upload request without content, sent as an application/json part",
            requiredMode = REQUIRED)
    private BankImportCreateRequest meta;

    private BankImportUploadForm() {}
}
