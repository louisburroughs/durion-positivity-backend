package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for stopping a labor session.
 * Empty body - stopTime is captured server-side.
 * 
 * <p>Implements CAP-005 Story #159 - Record Labor Performed
 */
@Data
@NoArgsConstructor
@Schema(description = "Request to stop a labor session (empty body)")
public class StopLaborRequest {
    // Empty on purpose - idempotency handled via header
}
