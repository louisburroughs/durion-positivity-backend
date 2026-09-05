package com.positivity.securityservice.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.security.common.RequiredPermissionsOpenApiAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Regression guard for issue #1721: this module registers its own {@link OperationCustomizer}
 * ({@link ProducibleResponsesOperationCustomizer}) and pos-security-common's auto-configured
 * {@code x-required-permissions} customizer must keep being registered alongside it. Before this
 * change the auto-configuration was guarded by {@code @ConditionalOnMissingBean(OperationCustomizer.class)},
 * which would have silently dropped the {@code x-required-permissions} extension from this
 * service's spec the moment a second customizer appeared.
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
