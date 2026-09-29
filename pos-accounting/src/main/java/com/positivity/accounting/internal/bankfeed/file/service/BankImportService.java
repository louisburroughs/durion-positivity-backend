package com.positivity.accounting.internal.bankfeed.file.service;

import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCommitRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCommitResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCreateRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportDiscardRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportListResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportMappingRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportRowListResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportRowResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportRowUpdateRequest;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportRowStatus;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportStatus;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The statement-file import lifecycle (SPEC-manual-bank-reconciliation §3.3, §3.8, §4.3–§4.5, §6.1;
 * story S3, #2302): upload, preview, mapping, row correction, commit through the intake port, discard
 * and the audited download of the retained file. Every refusal is a {@code BankRecException}.
 */
public interface BankImportService {

    /** The retained raw file of an import. */
    record ImportFile(
            byte @NonNull [] content,
            @NonNull String fileName,
            @NonNull String contentType) {}

    /**
     * Uploads a file: the JSON body with base64 {@code content}, or a multipart upload's {@code meta}
     * with the {@code file} part's bytes.
     *
     * @param fileBytes the multipart file's bytes; null for a JSON upload
     * @param fileName the multipart file's name, when {@code meta} names none
     * @param contentType the multipart file's media type, when {@code meta} names none
     */
    @NonNull
    BankImportResponse create(
            @NonNull BankImportCreateRequest request,
            byte @Nullable [] fileBytes,
            @Nullable String fileName,
            @Nullable String contentType);

    @NonNull
    BankImportListResponse list(@Nullable UUID glAccountId, @Nullable BankImportStatus status, int page, int size);

    @NonNull
    BankImportResponse get(@NonNull UUID importId);

    @NonNull
    BankImportRowListResponse rows(@NonNull UUID importId, @Nullable BankImportRowStatus status, int page, int size);

    @NonNull
    BankImportResponse updateMapping(@NonNull UUID importId, @NonNull BankImportMappingRequest request);

    @NonNull
    BankImportRowResponse updateRow(
            @NonNull UUID importId, @NonNull UUID rowId, @NonNull BankImportRowUpdateRequest request);

    @NonNull
    BankImportCommitResponse commit(@NonNull UUID importId, @Nullable BankImportCommitRequest request);

    @NonNull
    BankImportResponse discard(@NonNull UUID importId, @NonNull BankImportDiscardRequest request);

    @NonNull
    ImportFile download(@NonNull UUID importId);
}
