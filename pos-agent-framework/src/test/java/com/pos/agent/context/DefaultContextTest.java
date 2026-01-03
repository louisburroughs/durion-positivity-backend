package com.pos.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for DefaultContext class.
 */
@DisplayName("DefaultContext")
class DefaultContextTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should create DefaultContext using builder")
        void shouldCreateDefaultContextUsingBuilder() {
            // When
            DefaultContext context = DefaultContext.builder().build();

            // Then
            assertThat(context).isNotNull();
            assertThat(context).isInstanceOf(AgentContext.class);
        }

        @Test
        @DisplayName("should set default agentDomain to 'default'")
        void shouldSetDefaultAgentDomain() {
            // When
            DefaultContext context = DefaultContext.builder().build();

            // Then
            assertThat(context.getAgentDomain()).isEqualTo("default");
        }

        @Test
        @DisplayName("should set default contextType to 'default-context'")
        void shouldSetDefaultContextType() {
            // When
            DefaultContext context = DefaultContext.builder().build();

            // Then
            assertThat(context.getContextType()).isEqualTo("default-context");
        }

        @Test
        @DisplayName("should generate contextId automatically")
        void shouldGenerateContextIdAutomatically() {
            // When
            DefaultContext context = DefaultContext.builder().build();

            // Then
            assertThat(context.getContextId())
                    .isNotNull()
                    .startsWith("context-");
        }

        @Test
        @DisplayName("should generate sessionId automatically")
        void shouldGenerateSessionIdAutomatically() {
            // When
            DefaultContext context = DefaultContext.builder().build();

            // Then
            assertThat(context.getSessionId())
                    .isNotNull()
                    .startsWith("session-");
        }

        @Test
        @DisplayName("should set createdAt timestamp automatically")
        void shouldSetCreatedAtTimestampAutomatically() {
            // Given
            Instant before = Instant.now();

            // When
            DefaultContext context = DefaultContext.builder().build();

            // Then
            Instant after = Instant.now();
            assertThat(context.getCreatedAt())
                    .isNotNull()
                    .isBetween(before, after);
        }

        @Test
        @DisplayName("should set lastUpdated to createdAt by default")
        void shouldSetLastUpdatedToCreatedAtByDefault() {
            // When
            DefaultContext context = DefaultContext.builder().build();

            // Then
            assertThat(context.getLastUpdated()).isEqualTo(context.getCreatedAt());
        }
    }

    @Nested
    @DisplayName("Property Tests")
    class PropertyTests {

        @Test
        @DisplayName("should support adding custom properties")
        void shouldSupportAddingCustomProperties() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .property("customKey", "customValue")
                    .build();

            // Then
            assertThat(context.getProperty("customKey")).isEqualTo("customValue");
        }

        @Test
        @DisplayName("should support adding multiple properties")
        void shouldSupportAddingMultipleProperties() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .property("key1", "value1")
                    .property("key2", 42)
                    .property("key3", true)
                    .build();

            // Then
            assertThat(context.getProperty("key1")).isEqualTo("value1");
            assertThat(context.getProperty("key2")).isEqualTo(42);
            assertThat(context.getProperty("key3")).isEqualTo(true);
        }

        @Test
        @DisplayName("should support adding properties map")
        void shouldSupportAddingPropertiesMap() {
            // Given
            Map<String, Object> props = new HashMap<>();
            props.put("mapKey1", "mapValue1");
            props.put("mapKey2", "mapValue2");

            // When
            DefaultContext context = DefaultContext.builder()
                    .properties(props)
                    .build();

            // Then
            assertThat(context.getProperties()).containsAllEntriesOf(props);
        }

        @Test
        @DisplayName("should initialize with empty properties by default")
        void shouldInitializeWithEmptyPropertiesByDefault() {
            // When
            DefaultContext context = DefaultContext.builder().build();

            // Then
            assertThat(context.getProperties())
                    .isNotNull()
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Description Tests")
    class DescriptionTests {

        @Test
        @DisplayName("should support setting description")
        void shouldSupportSettingDescription() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .description("Test description")
                    .build();

            // Then
            assertThat(context.getDescription()).isEqualTo("Test description");
        }

        @Test
        @DisplayName("should have null description by default")
        void shouldHaveNullDescriptionByDefault() {
            // When
            DefaultContext context = DefaultContext.builder().build();

            // Then
            assertThat(context.getDescription()).isNull();
        }
    }

    @Nested
    @DisplayName("Security Configuration Tests")
    class SecurityConfigurationTests {

        @Test
        @DisplayName("should support requiresAuthentication configuration")
        void shouldSupportRequiresAuthenticationConfiguration() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .requiresAuthentication(true)
                    .build();

            // Then
            assertThat(context.getProperty("requiresAuthentication")).isEqualTo(true);
        }

        @Test
        @DisplayName("should support requiresTLS13 configuration")
        void shouldSupportRequiresTLS13Configuration() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .requiresTLS13(true)
                    .build();

            // Then
            assertThat(context.getProperty("requiresTLS13")).isEqualTo(true);
        }

        @Test
        @DisplayName("should support requiresAuditTrail configuration")
        void shouldSupportRequiresAuditTrailConfiguration() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .requiresAuditTrail(true)
                    .build();

            // Then
            assertThat(context.getProperty("requiresAuditTrail")).isEqualTo(true);
        }

        @Test
        @DisplayName("should support requiresAdminRole configuration")
        void shouldSupportRequiresAdminRoleConfiguration() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .requiresAdminRole(true)
                    .build();

            // Then
            assertThat(context.getProperty("requiresAdminRole")).isEqualTo(true);
        }

        @Test
        @DisplayName("should support requiredPermission configuration")
        void shouldSupportRequiredPermissionConfiguration() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .requiredPermission("ADMIN_WRITE")
                    .build();

            // Then
            assertThat(context.getProperty("requiredPermission")).isEqualTo("ADMIN_WRITE");
        }

        @Test
        @DisplayName("should support multiple security configurations")
        void shouldSupportMultipleSecurityConfigurations() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .requiresAuthentication(true)
                    .requiresTLS13(true)
                    .requiresAuditTrail(true)
                    .requiresAdminRole(true)
                    .requiredPermission("SUPER_ADMIN")
                    .build();

            // Then
            assertThat(context.getProperty("requiresAuthentication")).isEqualTo(true);
            assertThat(context.getProperty("requiresTLS13")).isEqualTo(true);
            assertThat(context.getProperty("requiresAuditTrail")).isEqualTo(true);
            assertThat(context.getProperty("requiresAdminRole")).isEqualTo(true);
            assertThat(context.getProperty("requiredPermission")).isEqualTo("SUPER_ADMIN");
        }
    }

    @Nested
    @DisplayName("Service Configuration Tests")
    class ServiceConfigurationTests {

        @Test
        @DisplayName("should support serviceType configuration")
        void shouldSupportServiceTypeConfiguration() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .serviceType("REST")
                    .build();

            // Then
            assertThat(context.getProperty("serviceType")).isEqualTo("REST");
        }

        @Test
        @DisplayName("should support requestId configuration")
        void shouldSupportRequestIdConfiguration() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .requestId("req-123")
                    .build();

            // Then
            assertThat(context.getProperty("requestId")).isEqualTo("req-123");
        }

        @Test
        @DisplayName("should support secretsProvider configuration")
        void shouldSupportSecretsProviderConfiguration() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .secretsProvider("AWS_SECRETS_MANAGER")
                    .build();

            // Then
            assertThat(context.getProperty("secretsProvider")).isEqualTo("AWS_SECRETS_MANAGER");
        }

        @Test
        @DisplayName("should support multiple service configurations")
        void shouldSupportMultipleServiceConfigurations() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .serviceType("GRPC")
                    .requestId("req-456")
                    .secretsProvider("VAULT")
                    .build();

            // Then
            assertThat(context.getProperty("serviceType")).isEqualTo("GRPC");
            assertThat(context.getProperty("requestId")).isEqualTo("req-456");
            assertThat(context.getProperty("secretsProvider")).isEqualTo("VAULT");
        }
    }

    @Nested
    @DisplayName("Fluent API Tests")
    class FluentAPITests {

        @Test
        @DisplayName("should support fluent chaining with all builder methods")
        void shouldSupportFluentChainingWithAllBuilderMethods() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .description("Comprehensive test context")
                    .property("custom1", "value1")
                    .property("custom2", 100)
                    .requiresAuthentication(true)
                    .requiresTLS13(true)
                    .requiresAuditTrail(true)
                    .serviceType("REST")
                    .requestId("req-789")
                    .secretsProvider("VAULT")
                    .build();

            // Then
            assertThat(context.getDescription()).isEqualTo("Comprehensive test context");
            assertThat(context.getProperty("custom1")).isEqualTo("value1");
            assertThat(context.getProperty("custom2")).isEqualTo(100);
            assertThat(context.getProperty("requiresAuthentication")).isEqualTo(true);
            assertThat(context.getProperty("requiresTLS13")).isEqualTo(true);
            assertThat(context.getProperty("requiresAuditTrail")).isEqualTo(true);
            assertThat(context.getProperty("serviceType")).isEqualTo("REST");
            assertThat(context.getProperty("requestId")).isEqualTo("req-789");
            assertThat(context.getProperty("secretsProvider")).isEqualTo("VAULT");
        }

        @Test
        @DisplayName("should maintain type safety through fluent chaining")
        void shouldMaintainTypeSafetyThroughFluentChaining() {
            // When - This should compile without warnings
            DefaultContext.Builder builder = DefaultContext.builder()
                    .description("Type safe")
                    .property("key", "value");

            DefaultContext context = builder.build();

            // Then
            assertThat(context).isNotNull();
            assertThat(context.getDescription()).isEqualTo("Type safe");
        }
    }

    @Nested
    @DisplayName("Inheritance Tests")
    class InheritanceTests {

        @Test
        @DisplayName("should inherit from AgentContext")
        void shouldInheritFromAgentContext() {
            // When
            DefaultContext context = DefaultContext.builder().build();

            // Then
            assertThat(context).isInstanceOf(AgentContext.class);
        }

        @Test
        @DisplayName("should have access to AgentContext methods")
        void shouldHaveAccessToAgentContextMethods() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .property("test", "value")
                    .build();

            // Then - These are AgentContext methods
            assertThat(context.getContextId()).isNotNull();
            assertThat(context.getSessionId()).isNotNull();
            assertThat(context.getCreatedAt()).isNotNull();
            assertThat(context.getLastUpdated()).isNotNull();
            assertThat(context.getContextType()).isNotNull();
            assertThat(context.getAgentDomain()).isNotNull();
            assertThat(context.getProperties()).isNotNull();
            assertThat(context.getProperty("test")).isEqualTo("value");
        }
    }

    @Nested
    @DisplayName("Edge Cases and Validation Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("should handle empty description")
        void shouldHandleEmptyDescription() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .description("")
                    .build();

            // Then
            assertThat(context.getDescription()).isEmpty();
        }

        @Test
        @DisplayName("should handle null property values")
        void shouldHandleNullPropertyValues() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .property("nullKey", null)
                    .build();

            // Then
            assertThat(context.getProperties()).containsKey("nullKey");
            assertThat(context.getProperty("nullKey")).isNull();
        }

        @Test
        @DisplayName("should handle complex nested objects as properties")
        void shouldHandleComplexNestedObjectsAsProperties() {
            // Given
            Map<String, Object> nestedMap = new HashMap<>();
            nestedMap.put("nested1", "value1");
            nestedMap.put("nested2", Map.of("deepNested", "deepValue"));

            // When
            DefaultContext context = DefaultContext.builder()
                    .property("complex", nestedMap)
                    .build();

            // Then
            assertThat(context.getProperty("complex")).isEqualTo(nestedMap);
        }

        @Test
        @DisplayName("should create multiple independent contexts")
        void shouldCreateMultipleIndependentContexts() {
            // When
            DefaultContext context1 = DefaultContext.builder()
                    .property("key", "value1")
                    .build();

            DefaultContext context2 = DefaultContext.builder()
                    .property("key", "value2")
                    .build();

            // Then
            assertThat(context1.getProperty("key")).isEqualTo("value1");
            assertThat(context2.getProperty("key")).isEqualTo("value2");
            assertThat(context1.getContextId()).isNotEqualTo(context2.getContextId());
        }

        @Test
        @DisplayName("should override property value when set multiple times")
        void shouldOverridePropertyValueWhenSetMultipleTimes() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .property("key", "originalValue")
                    .property("key", "updatedValue")
                    .build();

            // Then
            assertThat(context.getProperty("key")).isEqualTo("updatedValue");
        }

        @Test
        @DisplayName("should handle boolean false values correctly")
        void shouldHandleBooleanFalseValuesCorrectly() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .requiresAuthentication(false)
                    .requiresTLS13(false)
                    .build();

            // Then
            assertThat(context.getProperty("requiresAuthentication")).isEqualTo(false);
            assertThat(context.getProperty("requiresTLS13")).isEqualTo(false);
        }
    }

    @Nested
    @DisplayName("Real-World Usage Tests")
    class RealWorldUsageTests {

        @Test
        @DisplayName("should create production-ready secure context")
        void shouldCreateProductionReadySecureContext() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .description("Production API context")
                    .requiresAuthentication(true)
                    .requiresTLS13(true)
                    .requiresAuditTrail(true)
                    .serviceType("REST")
                    .requestId("req-prod-001")
                    .secretsProvider("AWS_SECRETS_MANAGER")
                    .property("environment", "production")
                    .property("region", "us-east-1")
                    .build();

            // Then
            assertThat(context.getDescription()).isEqualTo("Production API context");
            assertThat(context.getProperty("requiresAuthentication")).isEqualTo(true);
            assertThat(context.getProperty("requiresTLS13")).isEqualTo(true);
            assertThat(context.getProperty("requiresAuditTrail")).isEqualTo(true);
            assertThat(context.getProperty("serviceType")).isEqualTo("REST");
            assertThat(context.getProperty("environment")).isEqualTo("production");
        }

        @Test
        @DisplayName("should create development context with minimal security")
        void shouldCreateDevelopmentContextWithMinimalSecurity() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .description("Development test context")
                    .requiresAuthentication(false)
                    .serviceType("REST")
                    .property("environment", "development")
                    .build();

            // Then
            assertThat(context.getDescription()).isEqualTo("Development test context");
            assertThat(context.getProperty("requiresAuthentication")).isEqualTo(false);
            assertThat(context.getProperty("environment")).isEqualTo("development");
        }

        @Test
        @DisplayName("should create admin context with elevated permissions")
        void shouldCreateAdminContextWithElevatedPermissions() {
            // When
            DefaultContext context = DefaultContext.builder()
                    .description("Admin operations context")
                    .requiresAuthentication(true)
                    .requiresAdminRole(true)
                    .requiredPermission("SYSTEM_ADMIN")
                    .requiresAuditTrail(true)
                    .property("userId", "admin-001")
                    .build();

            // Then
            assertThat(context.getProperty("requiresAuthentication")).isEqualTo(true);
            assertThat(context.getProperty("requiresAdminRole")).isEqualTo(true);
            assertThat(context.getProperty("requiredPermission")).isEqualTo("SYSTEM_ADMIN");
            assertThat(context.getProperty("requiresAuditTrail")).isEqualTo(true);
        }
    }
}
