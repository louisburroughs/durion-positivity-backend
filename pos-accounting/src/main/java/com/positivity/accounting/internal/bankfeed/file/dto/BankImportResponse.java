package com.positivity.accounting.internal.bankfeed.file.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.bankfeed.file.enums.BankImportRowStatus;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A statement-file import (SPEC-manual-bank-reconciliation §3.3, §4.3, §4.4, §6.1; story S3, #2302):
 * header, mapping and options, counts, status, outcome ids and — on a single read — the preview.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A statement-file import: header, mapping, counts, status, outcome ids and preview")
public class BankImportResponse {

    @Schema(description = "Import id", requiredMode = REQUIRED)
    private UUID importId;

    @Schema(description = "The client's requestId", requiredMode = REQUIRED)
    private UUID requestId;

    @Schema(description = "GL bank account", requiredMode = REQUIRED)
    private UUID glAccountId;

    @Schema(description = "Account code shown beside the id", example = "1010", requiredMode = REQUIRED)
    private String glAccountCode;

    @Schema(description = "Account name shown beside the id", example = "Operating Checking", requiredMode = REQUIRED)
    private String glAccountName;

    @Schema(description = "ISO 4217 code of the amounts", example = "USD", requiredMode = REQUIRED)
    private String currency;

    @Schema(description = "File format", example = "CSV", requiredMode = REQUIRED)
    private String formatCode;

    @Schema(description = "Uploaded file name", example = "september.csv")
    private String fileName;

    @Schema(description = "Uploaded media type", example = "text/csv")
    private String contentType;

    @Schema(description = "File size in bytes", example = "18234")
    private Long fileSize;

    @Schema(description = "SHA-256 of the file's bytes", requiredMode = REQUIRED)
    private String fileSha256;

    @Schema(description = "Import status", requiredMode = REQUIRED)
    private BankImportStatus status;

    @Schema(description = "Whether the file's columns still need a mapping (status UPLOADED)", requiredMode = REQUIRED)
    private Boolean mappingRequired;

    @Schema(description = "The file's column labels in file order", requiredMode = REQUIRED)
    private List<String> columns;

    @Schema(description = "Whether the first record was read as a header row")
    private Boolean headerRow;

    @Schema(description = "Statement header", requiredMode = REQUIRED)
    private BankImportStatementHeader statement;

    @Schema(description = "Split points of the window, when split")
    private List<BankImportSplitPoint> splitAt;

    @Schema(description = "The column mapping the rows were read with")
    private Map<String, Object> columnMapping;

    @Schema(description = "Sign convention", example = "SIGNED_AMOUNT")
    private String signConvention;

    @Schema(description = "Date pattern, when set", example = "MM/dd/yyyy")
    private String dateFormat;

    @Schema(description = "Decimal separator option", example = "DECIMAL_POINT")
    private String decimalFormat;

    @Schema(description = "Character set", example = "UTF-8")
    private String encoding;

    @Schema(description = "Field delimiter", example = ",")
    private String delimiter;

    @Schema(description = "Whether the commit saves the mapping on the account profile it creates")
    private Boolean saveMappingAsDefault;

    @Schema(description = "The gap acknowledgement the commit hands to the intake")
    private String gapAcknowledgement;

    @Schema(description = "The COMMITTED statement this corrected file supersedes at commit (§4.9 path 3)")
    private UUID supersedesStatementId;

    @Schema(description = "Why it supersedes it")
    private String supersessionJustification;

    @Schema(description = "Rows in the file", example = "240", requiredMode = REQUIRED)
    private Integer rowCount;

    @Schema(description = "Rows PARSED, CORRECTED (or COMMITTED)", example = "237", requiredMode = REQUIRED)
    private Integer acceptedCount;

    @Schema(description = "Rows REJECTED", example = "3", requiredMode = REQUIRED)
    private Integer rejectedCount;

    @Schema(
            description = "Rows POSSIBLE_DUPLICATE (after commit: transactions entered as possible duplicates)",
            example = "0",
            requiredMode = REQUIRED)
    private Integer possibleDuplicateCount;

    @Schema(description = "Rows SKIPPED", example = "0", requiredMode = REQUIRED)
    private Integer skippedCount;

    @Schema(description = "Rows OUT_OF_WINDOW", example = "0", requiredMode = REQUIRED)
    private Integer outOfWindowCount;

    @Schema(description = "The preview: first rows with running balance and E1 per segment (single reads only)")
    private Preview preview;

    @Schema(description = "The committed statement (the last segment's when split)")
    private UUID statementId;

    @Schema(description = "Every committed statement in window order")
    private List<UUID> statementIds;

    @Schema(description = "The reconciliation started with the commit (startReconciliation), if any")
    private UUID reconciliationId;

    @Schema(description = "When the raw file becomes eligible for the retention purge", example = "2033-09-28")
    private LocalDate retentionUntil;

    @Schema(description = "Whether the raw file was purged after retention", requiredMode = REQUIRED)
    private Boolean filePurged;

    @Schema(description = "Created at", requiredMode = REQUIRED)
    private Instant createdAt;

    @Schema(description = "Created by", requiredMode = REQUIRED)
    private String createdBy;

    @Schema(description = "Committed at")
    private Instant committedAt;

    @Schema(description = "Committed by")
    private String committedBy;

    @Schema(description = "Discarded at")
    private Instant discardedAt;

    @Schema(description = "Discarded by")
    private String discardedBy;

    @Schema(description = "Why the import was discarded")
    private String discardReason;

    @Schema(description = "Optimistic-lock version", example = "2", requiredMode = REQUIRED)
    private Long version;

    @Schema(
            description = "True when this response replays an earlier create with the same requestId",
            requiredMode = REQUIRED)
    private Boolean replayed;

    /** The preview of §4.4. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(
            name = "BankImportPreview",
            description = "First rows with their resulting sign and running balance,"
                    + " and opening + activity against closing per statement segment")
    public static class Preview {

        @Schema(description = "The first five rows of the file", requiredMode = REQUIRED)
        private List<PreviewRow> firstRows;

        @Schema(description = "E1 per segment: one, or one per split segment", requiredMode = REQUIRED)
        private List<SegmentTotal> segments;

        @Schema(description = "Whether opening + activity equals closing in every segment", requiredMode = REQUIRED)
        private Boolean ties;
    }

    /** A preview row. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "BankImportPreviewRow", description = "A row with its resulting sign and running balance")
    public static class PreviewRow {

        @Schema(description = "Row number", example = "1", requiredMode = REQUIRED)
        private Integer rowNumber;

        @Schema(description = "Posting date", example = "2026-09-02")
        private LocalDate date;

        @Schema(description = "Description", example = "ACH DEPOSIT")
        private String description;

        @Schema(description = "Amount, positive = cash in", example = "500.0000")
        private BigDecimal signedAmount;

        @Schema(description = "Row status", requiredMode = REQUIRED)
        private BankImportRowStatus rowStatus;

        @Schema(
                description = "Opening balance plus the counted rows so far; null for a row not counted",
                example = "12845.6700")
        private BigDecimal runningBalance;
    }

    /** E1 of one segment. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "BankImportSegmentTotal", description = "Opening + activity against closing for one statement")
    public static class SegmentTotal {

        @Schema(description = "First day", example = "2026-09-01", requiredMode = REQUIRED)
        private LocalDate startDate;

        @Schema(description = "Last day", example = "2026-09-30", requiredMode = REQUIRED)
        private LocalDate endDate;

        @Schema(description = "Opening balance", example = "12345.67", requiredMode = REQUIRED)
        private BigDecimal openingBalance;

        @Schema(description = "Closing balance", example = "12830.67", requiredMode = REQUIRED)
        private BigDecimal closingBalance;

        @Schema(description = "Sum of the counted rows", example = "485.0000", requiredMode = REQUIRED)
        private BigDecimal activityTotal;

        @Schema(description = "Opening + activity", example = "12830.6700", requiredMode = REQUIRED)
        private BigDecimal expectedClosing;

        @Schema(description = "Expected closing − closing", example = "0.0000", requiredMode = REQUIRED)
        private BigDecimal difference;

        @Schema(description = "Whether the difference is within one minor unit (E1)", requiredMode = REQUIRED)
        private Boolean ties;

        @Schema(description = "Rows counted in the segment", example = "2", requiredMode = REQUIRED)
        private Integer transactionCount;
    }
}
