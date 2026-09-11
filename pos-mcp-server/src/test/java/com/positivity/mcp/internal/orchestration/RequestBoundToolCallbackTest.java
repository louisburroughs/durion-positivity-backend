package com.positivity.mcp.internal.orchestration;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.tenancy.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The request context a tool execution runs under when the Spring AI tool loop runs it on another
 * thread (ADR-0062 plan WS6).
 *
 * <p>These tests deliberately never enable Reactor's automatic context propagation: that JVM-wide
 * hook only exists on the {@code alpha} profile ({@code EvalTurnTracePropagation}), and the tenant a
 * streamed tool call writes its {@code mcp_tool_invocation_log} row under must not depend on the
 * profile. Executing the callback on a plain executor is the non-alpha case exactly — nothing
 * carries a ThreadLocal across the hop but the callback itself.
 */
class RequestBoundToolCallbackTest {

    private static final CurrentUserContext CALLER = new CurrentUserContext(
            "alice",
            UUID.fromString("01900000-0000-7000-8000-00000000000a"),
            "ROLE_TECH",
            Set.of("ROLE_TECH"),
            Set.of("ROLE_TECH"),
            Set.of("AUTHENTICATED"));

    private final RequestScopedUserContext userContext = new RequestScopedUserContext();

    @AfterEach
    void tearDown() {
        userContext.clear();
        TenantContext.clear();
    }

    @Test
    @DisplayName("a tool executed on another thread runs under the request's tenant, without Reactor propagation")
    void toolExecution_offRequestThread_bindsRequestTenant() throws Exception {
        AtomicReference<UUID> observed = new AtomicReference<>();
        List<ToolCallback> bound = TenantContext.callAs(TENANT_B, () -> {
            userContext.set(CALLER, "Bearer token");
            return RequestBoundToolCallback.bindCurrentRequest(List.of(recordingCallback(observed)), userContext);
        });
        // The manager clears the request thread's context before the first token is emitted.
        userContext.clear();

        runOnAnotherThread(() -> bound.getFirst().call("{}"));

        assertThat(observed.get())
                .as("the mcp_tool_invocation_log insert must see the caller's tenant, not nothing")
                .isEqualTo(TENANT_B);
    }

    @Test
    @DisplayName("the executing thread sees the caller as well as the tenant")
    void toolExecution_offRequestThread_bindsCallerAndAuthHeader() throws Exception {
        AtomicReference<UUID> observedTenant = new AtomicReference<>();
        AtomicReference<String> observedUser = new AtomicReference<>();
        AtomicReference<String> observedAuth = new AtomicReference<>();
        List<ToolCallback> bound = TenantContext.callAs(TENANT_A, () -> {
            userContext.set(CALLER, "Bearer token");
            return RequestBoundToolCallback.bindCurrentRequest(
                    List.of(callback(() -> {
                        observedTenant.set(TenantContext.current().orElse(null));
                        observedUser.set(userContext
                                .current()
                                .map(CurrentUserContext::username)
                                .orElse(null));
                        observedAuth.set(userContext.currentAuthHeader().orElse(null));
                        return "ok";
                    })),
                    userContext);
        });
        userContext.clear();

        runOnAnotherThread(() -> bound.getFirst().call("{}"));

        assertThat(observedTenant.get()).isEqualTo(TENANT_A);
        assertThat(observedUser.get()).isEqualTo("alice");
        assertThat(observedAuth.get()).isEqualTo("Bearer token");
    }

    @Test
    @DisplayName("the executing thread is left as it was found")
    void toolExecution_offRequestThread_restoresThreadAfterwards() throws Exception {
        List<ToolCallback> bound = TenantContext.callAs(TENANT_B, () -> {
            userContext.set(CALLER, "Bearer token");
            return RequestBoundToolCallback.bindCurrentRequest(List.of(callback(() -> "ok")), userContext);
        });
        userContext.clear();

        AtomicReference<Optional<UUID>> tenantAfter = new AtomicReference<>();
        AtomicReference<Optional<CurrentUserContext>> callerAfter = new AtomicReference<>();
        runOnAnotherThread(() -> {
            String result = bound.getFirst().call("{}");
            tenantAfter.set(TenantContext.current());
            callerAfter.set(userContext.current());
            return result;
        });

        assertThat(tenantAfter.get())
                .as("a pooled tool thread must not keep one caller's tenant for the next one")
                .isEmpty();
        assertThat(callerAfter.get()).isEmpty();
    }

    @Test
    @DisplayName("a tool run on the request thread restores the caller that was already bound there")
    void toolExecution_onRequestThread_restoresPreviousCaller() {
        CurrentUserContext other = new CurrentUserContext(
                "bob",
                UUID.fromString("01900000-0000-7000-8000-00000000000b"),
                "ROLE_TECH",
                Set.of("ROLE_TECH"),
                Set.of("ROLE_TECH"),
                Set.of("AUTHENTICATED"));
        TenantContext.runAs(TENANT_A, () -> {
            userContext.set(CALLER, "Bearer token");
            List<ToolCallback> bound =
                    RequestBoundToolCallback.bindCurrentRequest(List.of(callback(() -> "ok")), userContext);
            userContext.set(other, "Bearer other");

            bound.getFirst().call("{}");

            assertThat(userContext.current()).contains(other);
            assertThat(userContext.currentAuthHeader()).contains("Bearer other");
            assertThat(TenantContext.current()).contains(TENANT_A);
        });
    }

    @Test
    @DisplayName("a tenant with no caller still binds: the audit row is scoped even when the caller is unknown")
    void bindCurrentRequest_tenantWithoutCaller_stillBinds() throws Exception {
        AtomicReference<UUID> observed = new AtomicReference<>();
        List<ToolCallback> bound = TenantContext.callAs(
                TENANT_B,
                () -> RequestBoundToolCallback.bindCurrentRequest(List.of(recordingCallback(observed)), userContext));

        runOnAnotherThread(() -> bound.getFirst().call("{}"));

        assertThat(observed.get()).isEqualTo(TENANT_B);
    }

    @Test
    @DisplayName("with neither a caller nor a tenant bound the callbacks are returned untouched")
    void bindCurrentRequest_noContext_returnsCallbacksUnchanged() {
        List<ToolCallback> callbacks = List.of(callback(() -> "ok"));

        assertThat(RequestBoundToolCallback.bindCurrentRequest(callbacks, userContext))
                .isSameAs(callbacks);
        assertThat(RequestBoundToolCallback.bindCurrentRequest(callbacks, null)).isSameAs(callbacks);
    }

    private void runOnAnotherThread(java.util.concurrent.Callable<String> work) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = executor.submit(work);
            assertThat(result.get(5, TimeUnit.SECONDS)).isNotNull();
        } finally {
            executor.shutdownNow();
        }
    }

    private ToolCallback recordingCallback(AtomicReference<UUID> observed) {
        return callback(() -> {
            observed.set(TenantContext.current().orElse(null));
            return "ok";
        });
    }

    private ToolCallback callback(java.util.function.Supplier<String> body) {
        return new ToolCallback() {

            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name("probe")
                        .description("records the context it executes under")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return body.get();
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return body.get();
            }
        };
    }
}
