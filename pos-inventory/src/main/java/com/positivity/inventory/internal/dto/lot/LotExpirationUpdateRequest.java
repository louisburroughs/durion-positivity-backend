package com.positivity.inventory.internal.dto.lot;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Request to set or clear a lot's expiration and alert dates (odoo-parity E3, issue #1047). Both
 * fields are optional and set independently: a null clears the corresponding date. Clearing an
 * expiration also clears the emit-once alert bookkeeping so a re-dated lot can alert afresh.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Set or clear a lot's expiration date and alert-window date")
public class LotExpirationUpdateRequest {

    @Schema(
            description = "Expiration date of the lot (null clears it)",
            example = "2027-01-31",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate expirationDate;

    @Schema(
            description = "Date the lot enters its expiring-soon alert window (null means no early alert)",
            example = "2027-01-01",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate alertDate;
}
