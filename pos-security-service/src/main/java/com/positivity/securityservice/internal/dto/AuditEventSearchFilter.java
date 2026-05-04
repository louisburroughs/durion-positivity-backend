package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Rich filter criteria for the audit event search endpoint (B-3).
 * All fields are optional; an empty filter returns all events paged.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Filter criteria for audit event search")
public class AuditEventSearchFilter {
  @Schema(description = "Inclusive start timestamp (ISO-8601)", example = "2026-01-01T00:00:00Z")
  private Instant fromDate;

  @Schema(description = "Exclusive end timestamp (ISO-8601)", example = "2026-12-31T23:59:59Z")
  private Instant toDate;

  @Schema(description = "Actor username/service principal")
  private String actorId;

  @Schema(description = "Workorder UUID - one word per workspace naming policy")
  private UUID workorderId;

  @Schema(description = "Movement UUID")
  private UUID movementId;

  @Schema(description = "Product UUID")
  private UUID productId;

  @Schema(description = "SKU code")
  private String sku;

  @Schema(description = "Event type code")
  private String eventType;

  @Schema(description = "Aggregate identifier (string)")
  private String aggregateId;

  @Schema(description = "Correlation ID UUID for tracing")
  private UUID correlationId;

  @Schema(description = "Reason code string")
  private String reasonCode;

  @Schema(description = "Cursor token for page-based navigation")
  private String pageToken;

  @Schema(description = "Location UUID list filter")
  private List<String> locationIds;
}
