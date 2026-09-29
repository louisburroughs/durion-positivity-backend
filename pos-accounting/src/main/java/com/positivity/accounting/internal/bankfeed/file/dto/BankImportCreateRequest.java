package com.positivity.accounting.internal.bankfeed.file.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Upload of a statement file (SPEC-manual-bank-reconciliation §4.3, §6.1; story S3, #2302): the JSON
 * body with base64 {@code content}, or the {@code meta} part of a multipart upload whose {@code file}
 * part carries the bytes (then {@code content} is omitted). The service validates the shape itself, so
 * every refusal answers {@code VALIDATION_ERROR} naming its field.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A bank statement file with its statement header, column mapping and parser options")
public class BankImportCreateRequest {

    @Schema(description = "Reconcilable BANK_CASH GL account the statement belongs to", requiredMode = REQUIRED)
    private UUID glAccountId;

    @Schema(
            description = "Caller-generated UUIDv7; a replay with the same payload returns the original import",
            requiredMode = REQUIRED)
    private UUID requestId;

    @Schema(description = "File format; CSV in phase 1", example = "CSV", requiredMode = REQUIRED)
    private String formatCode;

    @Schema(
            description = "Base64 of the file's bytes (JSON upload only; omitted in a multipart upload)",
            example = "ZGF0ZSxkZXNjcmlwdGlvbixhbW91bnQKMjAyNi0wOS0wMixBQ0ggREVQT1NJVCw1MDAuMDA=")
    private String content;

    @Schema(description = "Name of the uploaded file", example = "september.csv")
    private String fileName;

    @Schema(description = "Media type of the file (defaults to text/csv)", example = "text/csv")
    private String contentType;

    @Schema(
            description = "ISO 4217 code of the file's amounts; defaults to, and must equal, the account's currency",
            example = "USD")
    private String currency;

    @Schema(description = "Statement header as printed by the bank", requiredMode = REQUIRED)
    private BankImportStatementHeader statement;

    @Schema(
            description = "Column mapping {date, description, amount | debit + credit, reference, checkNumber,"
                    + " sourceTransactionId}, each a header name or a zero-based column index; defaults to the"
                    + " account's saved mapping, else date, description, amount, reference",
            example = "{\"date\":\"Posted Date\",\"description\":\"Payee\",\"amount\":\"Amount\"}")
    private Map<String, Object> columnMapping;

    @Schema(
            description =
                    "SIGNED_AMOUNT (default, positive = cash in), SIGNED_AMOUNT_INVERTED or" + " DEBIT_CREDIT_COLUMNS",
            example = "SIGNED_AMOUNT")
    private String signConvention;

    @Schema(
            description = "Date pattern of the file, e.g. dd/MM/yyyy; defaults to ISO and US dates",
            example = "MM/dd/yyyy")
    private String dateFormat;

    @Schema(description = "DECIMAL_POINT (default, 1,234.56) or DECIMAL_COMMA (1.234,56)", example = "DECIMAL_POINT")
    private String decimalFormat;

    @Schema(description = "Character set of the file (default UTF-8)", example = "UTF-8")
    private String encoding;

    @Schema(description = "Field delimiter: one character or TAB (default ,)", example = ",")
    private String delimiter;

    @Schema(
            description = "Justification (at least 10 characters) required when the statement does not continue"
                    + " the previous one — always for the account's first statement — and refused when it does",
            example = "First statement reconciled on this account")
    private String gapAcknowledgement;

    @Schema(
            description = "A COMMITTED statement of the same account this corrected file supersedes (§4.9 path 3):"
                    + " checked at upload and again at commit, where the old statement becomes SUPERSEDED, its rows"
                    + " EXCLUDED and a FINALIZED reconciliation of it INVALIDATED; not combined with splitAt",
            example = "019a0000-0000-7000-8000-000000000002")
    private UUID supersedesStatementId;

    @Schema(
            description = "Why the statement is superseded (at least 10 characters); required with"
                    + " supersedesStatementId and refused without it",
            example = "The bank reissued September with the missing wire of 2026-09-14")
    private String supersessionJustification;

    @Schema(
            description = "Split points: each date ends a segment committed as its own statement, with its keyed"
                    + " closing balance")
    private List<BankImportSplitPoint> splitAt;
}
