package com.positivity.bulkingest;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BulkIngestResponse {

    private int totalSubmitted;
    private int successCount;
    private int failureCount;
    private List<BulkIngestResult> results;
}
