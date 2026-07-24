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
        public void withAuthority() {}

        @PreAuthorize("isAuthenticated()")
        public void authenticatedOnly() {}

        public void noAnnotation() {}
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
}
