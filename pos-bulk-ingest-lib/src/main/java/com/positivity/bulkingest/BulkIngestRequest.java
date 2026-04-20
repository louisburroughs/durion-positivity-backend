package com.positivity.bulkingest;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
public class BulkIngestRequest<T> {

    @NotNull
    private UUID jobId;

    @NotNull
    private UUID locationId;

    @NotEmpty
    private List<T> records;

    private String operatorId;
}
