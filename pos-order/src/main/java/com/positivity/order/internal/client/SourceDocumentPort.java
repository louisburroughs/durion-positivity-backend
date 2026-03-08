package com.positivity.order.internal.client;

import com.positivity.order.internal.entity.SourceType;
import org.jspecify.annotations.NonNull;

import java.util.List;

public interface SourceDocumentPort {
    @NonNull
    List<SourceDocumentLine> fetchLines(@NonNull SourceType sourceType, @NonNull String sourceId);
}
