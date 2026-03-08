package com.positivity.order.internal.client;

import com.positivity.order.internal.entity.SourceType;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default (stub) implementation of SourceDocumentPort.
 * Returns no source lines.
 * Replace with pos-workorder/pos-accounting REST adapters in a future story.
 */
@Component
public class DefaultSourceDocumentPortAdapter implements SourceDocumentPort {

    @Override
    public @NonNull List<SourceDocumentLine> fetchLines(@NonNull SourceType sourceType, @NonNull String sourceId) {
        return List.of();
    }
}
