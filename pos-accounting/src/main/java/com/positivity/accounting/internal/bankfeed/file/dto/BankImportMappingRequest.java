package com.positivity.accounting.internal.bankfeed.file.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A new column mapping, sign convention or parser options for an import (SPEC §4.4, §6.1; story S3,
 * #2302). Every row is parsed again from the retained file, so earlier corrections, skips and duplicate
 * decisions are discarded. A widened or corrected header, the gap acknowledgement it is checked with,
 * and new split points may come with it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Column mapping, sign convention and parser options to re-parse an import with")
public class BankImportMappingRequest {

    @Schema(
            description = "Column mapping, each a header name or a zero-based column index",
            example = "{\"date\":\"Posted Date\",\"description\":\"Payee\",\"amount\":\"Amount\"}",
            requiredMode = REQUIRED)
    private Map<String, Object> columnMapping;

    @Schema(
            description = "SIGNED_AMOUNT, SIGNED_AMOUNT_INVERTED or DEBIT_CREDIT_COLUMNS; defaults to the"
                    + " import's current convention",
            example = "DEBIT_CREDIT_COLUMNS")
    private String signConvention;

    @Schema(description = "Date pattern; defaults to the import's current option", example = "dd/MM/yyyy")
    private String dateFormat;

    @Schema(description = "DECIMAL_POINT or DECIMAL_COMMA; defaults to the import's current option")
    private String decimalFormat;

    @Schema(description = "Character set; defaults to the import's current option", example = "UTF-8")
    private String encoding;

    @Schema(description = "Field delimiter; defaults to the import's current option", example = ",")
    private String delimiter;

    @Schema(description = "Store the mapping as the account's default for the next import")
    private Boolean saveAsAccountDefault;

    @Schema(description = "A corrected or widened statement header; the header checks run again")
    private BankImportStatementHeader statement;

    @Schema(
            description = "The gap acknowledgement the corrected header is checked with (at least 10 characters):"
                    + " absent keeps the stored one, blank clears it",
            example = "First statement on this account; earlier history is reconciled outside the system")
    private String gapAcknowledgement;

    @Schema(description = "New split points; an empty list removes them, absent keeps them")
    private List<BankImportSplitPoint> splitAt;

    @Schema(
            description = "The import version the change was made against; a stale one answers 409 OPTIMISTIC_LOCK",
            example = "3")
    private Long version;
}
