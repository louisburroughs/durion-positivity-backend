package com.positivity.bulkingest;

import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BulkIngestResult {

    private int rowIndex;
    private UUID entityId;
    private boolean success;
    private String errorCode;
    private String errorMessage;
}
