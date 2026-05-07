package com.positivity.inventory.internal.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerPage<T> {
    private List<T> entries;

    @Nullable
    private String nextPageToken;
}
