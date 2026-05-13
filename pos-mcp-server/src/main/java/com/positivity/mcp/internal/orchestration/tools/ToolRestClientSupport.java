package com.positivity.mcp.internal.orchestration.tools;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

final class ToolRestClientSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolRestClientSupport.class);
    private static final String TOOL_PACKAGE = "com.positivity.mcp.internal.orchestration.tools.";
    private static final StackWalker STACK_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private ToolRestClientSupport() {}

    static @NonNull RestClient instrumentedClient(RestClient.Builder restClientBuilder, @NonNull String baseUrl) {
        return instrumentedBuilder(restClientBuilder).baseUrl(baseUrl).build();
    }

    static RestClient.Builder instrumentedBuilder(RestClient.Builder restClientBuilder) {
        return restClientBuilder.clone().requestInterceptor(new ToolExecutionTimingInterceptor());
    }

    private static final class ToolExecutionTimingInterceptor implements ClientHttpRequestInterceptor {

        @Override
        public @NonNull ClientHttpResponse intercept(
                @NonNull HttpRequest request, byte @NonNull [] body, @NonNull ClientHttpRequestExecution execution)
                throws IOException {
            String toolInvocation = currentToolInvocation().orElse("unknown-tool");
            URI uri = request.getURI();
            long startNanos = System.nanoTime();
            LOGGER.debug("MCP tool http start tool={} method={} uri={}", toolInvocation, request.getMethod(), uri);
            try {
                ClientHttpResponse response = execution.execute(request, body);
                LOGGER.info(
                        "MCP tool http completed tool={} method={} uri={} status={} elapsedMs={}",
                        toolInvocation,
                        request.getMethod(),
                        uri,
                        response.getStatusCode().value(),
                        elapsedMs(startNanos));
                return response;
            } catch (IOException exception) {
                LOGGER.warn(
                        "MCP tool http failed tool={} method={} uri={} elapsedMs={} error={}",
                        toolInvocation,
                        request.getMethod(),
                        uri,
                        elapsedMs(startNanos),
                        exception.getClass().getSimpleName());
                throw exception;
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "MCP tool http failed tool={} method={} uri={} elapsedMs={} error={}",
                        toolInvocation,
                        request.getMethod(),
                        uri,
                        elapsedMs(startNanos),
                        exception.getClass().getSimpleName());
                throw exception;
            }
        }
    }

    private static @NonNull Optional<String> currentToolInvocation() {
        return STACK_WALKER.walk(frames -> frames.filter(
                        frame -> frame.getClassName().startsWith(TOOL_PACKAGE))
                .filter(frame -> !frame.getClassName().equals(ToolRestClientSupport.class.getName()))
                .filter(frame -> !frame.getMethodName().startsWith("lambda$"))
                .map(frame -> frame.getDeclaringClass().getSimpleName() + "." + frame.getMethodName())
                .findFirst());
    }

    private static long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
