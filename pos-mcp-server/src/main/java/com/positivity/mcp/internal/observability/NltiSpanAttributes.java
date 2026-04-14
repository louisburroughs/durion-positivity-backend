package com.positivity.mcp.internal.observability;

import io.opentelemetry.api.common.AttributeKey;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

public final class NltiSpanAttributes {

    public static final @NonNull AttributeKey<String> NLT_CORRELATION_ID =
            Objects.requireNonNull(AttributeKey.stringKey("nlt.correlationId"));
    public static final @NonNull AttributeKey<String> NLT_REQUEST_ID =
            Objects.requireNonNull(AttributeKey.stringKey("nlt.requestId"));
    public static final @NonNull AttributeKey<String> NLT_USER_ID =
            Objects.requireNonNull(AttributeKey.stringKey("nlt.userId"));
    public static final @NonNull AttributeKey<String> NLT_SESSION_ID =
            Objects.requireNonNull(AttributeKey.stringKey("nlt.sessionId"));

    private NltiSpanAttributes() {}
}
