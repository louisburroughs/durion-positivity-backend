package com.positivity.accounting.internal.bankrec.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The rows a bulk duplicate review changed (SPEC §4.5; story S2, #2301). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The reviewed bank transactions, in request order")
public class BankTransactionBatchResponse {

    @Schema(description = "The reviewed rows", requiredMode = REQUIRED)
    private List<BankTransactionResponse> transactions;
}
