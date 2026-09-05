package com.positivity.securityservice.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.security.common.ProducibleResponsesOperationCustomizer;
import com.positivity.security.common.RequiredPermissionsOpenApiAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Regression guard for issue #1721: {@code pos-security-common}'s auto-configuration must contribute both
 * {@code OperationCustomizer} beans — the required-permissions extension and the response-pruning customizer
 * (now platform-wide, see {@link ProducibleResponsesOperationCustomizer}) — when run alongside this module's
 * {@link OpenApiConfig}. Before the move, {@code producibleResponsesOperationCustomizer} was declared directly
 * in {@code OpenApiConfig}; it is now sourced entirely from the auto-configuration, so this test proves the
 * module gets it with no code of its own.
 */
@DisplayName("OpenApiConfig customizer registration (issue #1721)")
class OpenApiConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RequiredPermissionsOpenApiAutoConfiguration.class))
            .withUserConfiguration(OpenApiConfig.class);

    @Test
    @DisplayName("both the required-permissions and the producible-responses customizers are registered")
    void bothOperationCustomizersCoexist() {
        contextRunner.run(context -> {
            assertThat(context.getBeansOfType(OperationCustomizer.class))
                    .containsKeys("requiredPermissionsOperationCustomizer", "producibleResponsesOperationCustomizer")
                    .hasSize(2);
            assertThat(context.getBean("producibleResponsesOperationCustomizer"))
                    .isInstanceOf(ProducibleResponsesOperationCustomizer.class);
        });
    }
}
