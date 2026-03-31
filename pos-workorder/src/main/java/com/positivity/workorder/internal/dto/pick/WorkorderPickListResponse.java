package com.positivity.workorder.internal.dto.pick;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkorderPickListResponse {
  private UUID pickListId;
  private UUID workorderId;
  private String status;
  private int priority;

  @JsonFormat(shape = JsonFormat.Shape.STRING)
  private Instant dueAt;

  @JsonFormat(shape = JsonFormat.Shape.STRING)
  private Instant createdAt;

  @JsonFormat(shape = JsonFormat.Shape.STRING)
  private Instant updatedAt;
}
