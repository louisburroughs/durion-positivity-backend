package com.positivity.accounting.internal.bankrec.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The optional body of submit and finalize (SPEC §4.9, §6.3; story S5, #2304): the version the caller read.
 * Both commands are sent with {@code {}} or no body at all.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Submit or approve a reconciliation; the body is optional")
public class ReconciliationTransitionRequest {

    @Schema(
            description = "The reconciliation version the caller read; a stale one answers 409 OPTIMISTIC_LOCK",
            example = "3")
    private Long version;
}
