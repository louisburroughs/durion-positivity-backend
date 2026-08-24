package com.positivity.security.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Operation;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;

class RequiredPermissionsOpenApiAutoConfigurationTest {

    private final OperationCustomizer customizer =
            new RequiredPermissionsOpenApiAutoConfiguration().requiredPermissionsOperationCustomizer();

    /** No class-level @PreAuthorize, so each method is evaluated in isolation. */
    static class Controller {
        @PreAuthorize("hasAuthority('order:order:discount')")
        public void withAuthority() {
            // Intentionally empty: the @PreAuthorize expression above is the fixture, and the
            // customizer under test reads the annotation, never the body.
        }

        @PreAuthorize("isAuthenticated()")
        public void authenticatedOnly() {
            // Intentionally empty: the @PreAuthorize expression above is the fixture, and the
            // customizer under test reads the annotation, never the body.
        }

        @PreAuthorize("hasAnyAuthority('order:order:view', 'order:order:edit')")
        public void anyAuthority() {
            // Intentionally empty: the @PreAuthorize expression above is the fixture, and the
            // customizer under test reads the annotation, never the body.
        }

        @PreAuthorize("hasRole('ADMIN')")
        public void roleOnly() {
            // Intentionally empty: the @PreAuthorize expression above is the fixture, and the
            // customizer under test reads the annotation, never the body.
        }

        public void noAnnotation() {
            // Intentionally empty, and deliberately unannotated: the absence of @PreAuthorize is
            // what this fixture contributes.
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> requiredPermissions(String methodName) throws Exception {
        Method method = Controller.class.getMethod(methodName);
        HandlerMethod handlerMethod = new HandlerMethod(new Controller(), method);
        Operation operation = customizer.customize(new Operation(), handlerMethod);
        return operation.getExtensions() == null
                ? null
                : (List<String>) operation.getExtensions().get("x-required-permissions");
    }

    @Test
    void extractsHasAuthorityArgument() throws Exception {
        assertThat(requiredPermissions("withAuthority")).containsExactly("order:order:discount");
    }

    @Test
    void emitsAuthenticatedSentinelForIsAuthenticated() throws Exception {
        assertThat(requiredPermissions("authenticatedOnly")).containsExactly("AUTHENTICATED");
    }

    @Test
    void emitsAuthenticatedSentinelWhenUnguarded() throws Exception {
        assertThat(requiredPermissions("noAnnotation")).containsExactly("AUTHENTICATED");
    }

    @Test
    void extractsAllHasAnyAuthorityArguments() throws Exception {
        assertThat(requiredPermissions("anyAuthority")).containsExactly("order:order:view", "order:order:edit");
    }

    @Test
    void roleOnlyExpressionStaysFailClosed() throws Exception {
        // hasRole is not a permission code; the op emits no x-required-permissions (fail-closed),
        // rather than leaking "ADMIN" or being broadened to AUTHENTICATED.
        assertThat(requiredPermissions("roleOnly")).isNull();
    }
}
