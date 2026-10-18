package com.positivity.accounting.internal.billintake.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The drafts an upload created, one per file, in the order the files were sent. */
@Schema(description = "One draft per uploaded file, in upload order. A replay of the same requestId returns the same drafts.")
public record BillIntakeUploadResponse(List<BillIntakeItemResponse> items) {}
