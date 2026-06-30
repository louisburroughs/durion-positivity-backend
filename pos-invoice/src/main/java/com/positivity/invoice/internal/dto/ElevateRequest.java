package com.positivity.invoice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Request payload for the manager-approval elevation endpoint.
 *
 * <p>The caller (typically a service advisor who lacks {@code invoice:finalize:override})
 * supplies the employee number of a manager who is authorising finalization of the
 * referenced invoice. The endpoint verifies that the named manager holds the override
 * authority and mints a short-lived elevation token scoped to that invoice.
 */
@Schema(description = "Request payload for minting a manager-approval elevation token")
public class ElevateRequest {

    @NotBlank
    @Schema(
            description = "Employee number of the approving manager",
            example = "EMP-0001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String managerEmployeeNumber;

    @NotNull
    @Schema(
            description = "Invoice the elevation token will authorise",
            example = "123e4567-e89b-12d3-a456-426614174000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID invoiceId;

    @NonNull
    public String getManagerEmployeeNumber() {
        return managerEmployeeNumber;
    }

    public void setManagerEmployeeNumber(@NonNull String managerEmployeeNumber) {
        this.managerEmployeeNumber = managerEmployeeNumber;
    }

    @NonNull
    public UUID getInvoiceId() {
        return invoiceId;
    }

    public void setInvoiceId(@NonNull UUID invoiceId) {
        this.invoiceId = invoiceId;
    }
}
