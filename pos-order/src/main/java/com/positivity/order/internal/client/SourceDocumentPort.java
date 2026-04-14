package com.positivity.order.internal.client;

import com.positivity.order.internal.entity.SourceType;
import java.util.List;
import org.jspecify.annotations.NonNull;

public interface SourceDocumentPort {
    @NonNull
    List<SourceDocumentLine> fetchLines(@NonNull SourceType sourceType, @NonNull String sourceId);
}
