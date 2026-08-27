package com.positivity.inventory.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Pins that the documented environment variable actually controls
 * {@code pos.inventory.sku-category.resolve-from-replica} (#1535).
 *
 * <p>This replaces an earlier text test that grepped {@code application.yml} and
 * {@code README.md} for matching strings. That test was built on a false premise
 * — that without a yaml placeholder the variable would not bind — and it could
 * not have detected a binding failure even if one existed: string equality
 * between two files says nothing about how Spring resolves a property. It also
 * read {@code application.yml} by a relative path that only works under Surefire,
 * and would have broken the moment the placeholder was quoted or wrapped.
 *
 * <p>What actually resolves the variable is Spring Core's
 * {@link SystemEnvironmentPropertySource}, whose name check maps dots and dashes
 * onto underscores, plus Boot's {@code SystemEnvironmentPropertyMapper}, which
 * offers both candidate forms. So both consumers are exercised here: {@code
 * getProperty}/{@code containsProperty} is the path {@code @ConditionalOnProperty}
 * on {@code ReplicaSkuCategoryProvider} takes, and {@link Binder} is the path
 * {@code @Value} takes in {@code SkuCategoryCutoverServiceImpl}.
 */
@DisplayName("SKU_CATEGORY property binding")
class SkuCategoryPropertyBindingTest {

    private static final String ENV_VAR = "POS_INVENTORY_SKU_CATEGORY_RESOLVE_FROM_REPLICA";
    private static final String PROPERTY = "pos.inventory.sku-category.resolve-from-replica";
    private static final String CAP_ENV_VAR = "POS_INVENTORY_SKU_CATEGORY_IMPACT_SKU_CAP";
    private static final String CAP_PROPERTY = "pos.inventory.sku-category.impact-sku-cap";

    /** An environment whose ONLY source is the documented variable, exactly as a container sets it. */
    private static StandardEnvironment environmentWith(Map<String, Object> systemEnvironment) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment
                .getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, systemEnvironment));
        return environment;
    }

    @Test
    @DisplayName("the documented env var resolves the flag through both the conditional and @Value paths")
    void documentedEnvVarResolvesTheFlagThroughBothPaths() {
        StandardEnvironment environment = environmentWith(Map.of(ENV_VAR, "true"));

        // The @ConditionalOnProperty path.
        assertThat(environment.containsProperty(PROPERTY)).isTrue();
        assertThat(environment.getProperty(PROPERTY)).isEqualTo("true");

        // The @Value / Binder path.
        assertThat(Binder.get(environment).bind(PROPERTY, Boolean.class).orElse(false))
                .isTrue();
    }

    @Test
    @DisplayName("the cap env var resolves too, so the bound is operator-settable")
    void capEnvVarResolves() {
        StandardEnvironment environment = environmentWith(Map.of(CAP_ENV_VAR, "250"));

        assertThat(environment.getProperty(CAP_PROPERTY)).isEqualTo("250");
        assertThat(Binder.get(environment).bind(CAP_PROPERTY, Integer.class).orElse(0))
                .isEqualTo(250);
    }

    @Test
    @DisplayName("with the variable unset the flag does not resolve, so the conditional stays off")
    void unsetEnvVarLeavesTheFlagUnresolved() {
        StandardEnvironment environment = environmentWith(Map.of());

        assertThat(environment.containsProperty(PROPERTY)).isFalse();
        assertThat(Binder.get(environment).bind(PROPERTY, Boolean.class).orElse(false))
                .isFalse();
    }
}
