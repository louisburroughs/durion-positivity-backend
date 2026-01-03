package com.pos.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for AgentContext abstract class.
 * Tests are performed through DefaultContext since AgentContext is abstract.
 */
@DisplayName("AgentContext")
class AgentContextTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("should create context with all required fields")
        void shouldCreateContextWithAllFields() {
            // Given
            String contextId = "test-context-123";
            String sessionId = "test-session-456";
            Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
            Instant lastUpdated = Instant.parse("2026-01-01T01:00:00Z");
            String description = "Test context";
            Map<String, Object> properties = new HashMap<>();
            properties.put("key1", "value1");

            // When
            AgentContext context = new TestAgentContext.Builder()
                    .contextId(contextId)
                    .sessionId(sessionId)
                    .createdAt(createdAt)
                    .lastUpdated(lastUpdated)
                    .description(description)
                    .properties(properties)
                    .build();

            // Then
            assertThat(context.getContextId()).isEqualTo(contextId);
            assertThat(context.getSessionId()).isEqualTo(sessionId);
            assertThat(context.getCreatedAt()).isEqualTo(createdAt);
            assertThat(context.getLastUpdated()).isEqualTo(lastUpdated);
            assertThat(context.getDescription()).isEqualTo(description);
            assertThat(context.getProperties()).containsEntry("key1", "value1");
        }

        @Test
        @DisplayName("should generate default contextId when not provided")
        void shouldGenerateDefaultContextId() {
            // When
            AgentContext context = new TestAgentContext.Builder().build();

            // Then
            assertThat(context.getContextId())
                    .isNotNull()
                    .startsWith("context-");
        }

        @Test
        @DisplayName("should generate default sessionId when not provided")
        void shouldGenerateDefaultSessionId() {
            // When
            AgentContext context = new TestAgentContext.Builder().build();

            // Then
            assertThat(context.getSessionId())
                    .isNotNull()
                    .startsWith("session-");
        }

        @Test
        @DisplayName("should set createdAt to current time when not provided")
        void shouldSetDefaultCreatedAt() {
            // Given
            Instant before = Instant.now();

            // When
            AgentContext context = new TestAgentContext.Builder().build();

            // Then
            Instant after = Instant.now();
            assertThat(context.getCreatedAt())
                    .isNotNull()
                    .isBetween(before, after);
        }

        @Test
        @DisplayName("should set lastUpdated to createdAt when not provided")
        void shouldSetLastUpdatedToCreatedAt() {
            // When
            AgentContext context = new TestAgentContext.Builder().build();

            // Then
            assertThat(context.getLastUpdated())
                    .isNotNull()
                    .isEqualTo(context.getCreatedAt());
        }

        @Test
        @DisplayName("should initialize empty properties map by default")
        void shouldInitializeEmptyPropertiesMap() {
            // When
            AgentContext context = new TestAgentContext.Builder().build();

            // Then
            assertThat(context.getProperties())
                    .isNotNull()
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Getter Tests")
    class GetterTests {

        @Test
        @DisplayName("should return correct contextType")
        void shouldReturnContextType() {
            // When
            AgentContext context = new TestAgentContext.Builder().build();

            // Then
            assertThat(context.getContextType()).isEqualTo("test-context");
        }

        @Test
        @DisplayName("should return correct agentDomain")
        void shouldReturnAgentDomain() {
            // When
            AgentContext context = new TestAgentContext.Builder().build();

            // Then
            assertThat(context.getAgentDomain()).isEqualTo("test");
        }

        @Test
        @DisplayName("should return property by key")
        void shouldReturnPropertyByKey() {
            // Given
            AgentContext context = new TestAgentContext.Builder()
                    .property("testKey", "testValue")
                    .build();

            // When
            Object value = context.getProperty("testKey");

            // Then
            assertThat(value).isEqualTo("testValue");
        }

        @Test
        @DisplayName("should return null for non-existent property")
        void shouldReturnNullForNonExistentProperty() {
            // Given
            AgentContext context = new TestAgentContext.Builder().build();

            // When
            Object value = context.getProperty("nonExistent");

            // Then
            assertThat(value).isNull();
        }
    }

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should build context with single property")
        void shouldBuildWithSingleProperty() {
            // When
            AgentContext context = new TestAgentContext.Builder()
                    .property("key1", "value1")
                    .build();

            // Then
            assertThat(context.getProperty("key1")).isEqualTo("value1");
        }

        @Test
        @DisplayName("should build context with multiple properties")
        void shouldBuildWithMultipleProperties() {
            // When
            AgentContext context = new TestAgentContext.Builder()
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
        @DisplayName("should build context with properties map")
        void shouldBuildWithPropertiesMap() {
            // Given
            Map<String, Object> props = new HashMap<>();
            props.put("key1", "value1");
            props.put("key2", "value2");

            // When
            AgentContext context = new TestAgentContext.Builder()
                    .properties(props)
                    .build();

            // Then
            assertThat(context.getProperties()).containsAllEntriesOf(props);
        }

        @Test
        @DisplayName("should build context with description")
        void shouldBuildWithDescription() {
            // When
            AgentContext context = new TestAgentContext.Builder()
                    .description("Test description")
                    .build();

            // Then
            assertThat(context.getDescription()).isEqualTo("Test description");
        }

        @Test
        @DisplayName("should support fluent chaining")
        void shouldSupportFluentChaining() {
            // When
            AgentContext context = new TestAgentContext.Builder()
                    .description("Fluent test")
                    .property("key1", "value1")
                    .property("key2", "value2")
                    .build();

            // Then
            assertThat(context.getDescription()).isEqualTo("Fluent test");
            assertThat(context.getProperty("key1")).isEqualTo("value1");
            assertThat(context.getProperty("key2")).isEqualTo("value2");
        }
    }

    @Nested
    @DisplayName("Security Properties Tests")
    class SecurityPropertiesTests {

        @Test
        @DisplayName("should set requiresAuthentication property")
        void shouldSetRequiresAuthentication() {
            // When
            AgentContext context = new TestAgentContext.Builder()
                    .requiresAuthentication(true)
                    .build();

            // Then
            assertThat(context.getProperty("requiresAuthentication")).isEqualTo(true);
        }

        @Test
        @DisplayName("should set requiresTLS13 property")
        void shouldSetRequiresTLS13() {
            // When
            AgentContext context = new TestAgentContext.Builder()
                    .requiresTLS13(true)
                    .build();

            // Then
            assertThat(context.getProperty("requiresTLS13")).isEqualTo(true);
        }

        @Test
        @DisplayName("should set requiresAuditTrail property")
        void shouldSetRequiresAuditTrail() {
            // When
            AgentContext context = new TestAgentContext.Builder()
                    .requiresAuditTrail(true)
                    .build();

            // Then
            assertThat(context.getProperty("requiresAuditTrail")).isEqualTo(true);
        }

        @Test
        @DisplayName("should set requiresAdminRole property")
        void shouldSetRequiresAdminRole() {
            // When
            AgentContext context = new TestAgentContext.Builder()
                    .requiresAdminRole(true)
                    .build();

            // Then
            assertThat(context.getProperty("requiresAdminRole")).isEqualTo(true);
        }

        @Test
        @DisplayName("should set requiredPermission property")
        void shouldSetRequiredPermission() {
            // When
            AgentContext context = new TestAgentContext.Builder()
                    .requiredPermission("ADMIN_READ")
                    .build();

            // Then
            assertThat(context.getProperty("requiredPermission")).isEqualTo("ADMIN_READ");
        }

        @Test
        @DisplayName("should set multiple security properties")
        void shouldSetMultipleSecurityProperties() {
            // When
            AgentContext context = new TestAgentContext.Builder()
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
    @DisplayName("Service Properties Tests")
    class ServicePropertiesTests {

        @Test
        @DisplayName("should set serviceType property")
        void shouldSetServiceType() {
            // When
            AgentContext context = new TestAgentContext.Builder()
                    .serviceType("REST")
                    .build();

            // Then
            assertThat(context.getProperty("serviceType")).isEqualTo("REST");
        }

        @Test
        @DisplayName("should set requestId property")
        void shouldSetRequestId() {
            // When
            AgentContext context = new TestAgentContext.Builder()
                    .requestId("req-12345")
                    .build();

            // Then
            assertThat(context.getProperty("requestId")).isEqualTo("req-12345");
        }

        @Test
        @DisplayName("should set secretsProvider property")
        void shouldSetSecretsProvider() {
            // When
            AgentContext context = new TestAgentContext.Builder()
                    .secretsProvider("AWS_SECRETS_MANAGER")
                    .build();

            // Then
            assertThat(context.getProperty("secretsProvider")).isEqualTo("AWS_SECRETS_MANAGER");
        }

        @Test
        @DisplayName("should set multiple service properties")
        void shouldSetMultipleServiceProperties() {
            // When
            AgentContext context = new TestAgentContext.Builder()
                    .serviceType("GRPC")
                    .requestId("req-67890")
                    .secretsProvider("VAULT")
                    .build();

            // Then
            assertThat(context.getProperty("serviceType")).isEqualTo("GRPC");
            assertThat(context.getProperty("requestId")).isEqualTo("req-67890");
            assertThat(context.getProperty("secretsProvider")).isEqualTo("VAULT");
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("should handle null property value")
        void shouldHandleNullPropertyValue() {
            // When
            AgentContext context = new TestAgentContext.Builder()
                    .property("nullKey", null)
                    .build();

            // Then
            assertThat(context.getProperties()).containsKey("nullKey");
            assertThat(context.getProperty("nullKey")).isNull();
        }

        @Test
        @DisplayName("should handle empty string property value")
        void shouldHandleEmptyStringPropertyValue() {
            // When
            AgentContext context = new TestAgentContext.Builder()
                    .property("emptyKey", "")
                    .build();

            // Then
            assertThat(context.getProperty("emptyKey")).isEqualTo("");
        }

        @Test
        @DisplayName("should handle complex object as property")
        void shouldHandleComplexObjectAsProperty() {
            // Given
            Map<String, String> complexObject = new HashMap<>();
            complexObject.put("nested", "value");

            // When
            AgentContext context = new TestAgentContext.Builder()
                    .property("complex", complexObject)
                    .build();

            // Then
            assertThat(context.getProperty("complex")).isEqualTo(complexObject);
        }

        @Test
        @DisplayName("should override property with same key")
        void shouldOverridePropertyWithSameKey() {
            // When
            AgentContext context = new TestAgentContext.Builder()
                    .property("key", "value1")
                    .property("key", "value2")
                    .build();

            // Then
            assertThat(context.getProperty("key")).isEqualTo("value2");
        }
    }

    @Nested
    @DisplayName("Properties Map Tests")
    class PropertiesMapTests {

        @Test
        @DisplayName("should merge properties from map with existing properties")
        void shouldMergePropertiesFromMap() {
            // Given
            Map<String, Object> additionalProps = new HashMap<>();
            additionalProps.put("key2", "value2");
            additionalProps.put("key3", "value3");

            // When
            AgentContext context = new TestAgentContext.Builder()
                    .property("key1", "value1")
                    .properties(additionalProps)
                    .build();

            // Then
            assertThat(context.getProperties())
                    .hasSize(3)
                    .containsEntry("key1", "value1")
                    .containsEntry("key2", "value2")
                    .containsEntry("key3", "value3");
        }

        @Test
        @DisplayName("should handle empty properties map")
        void shouldHandleEmptyPropertiesMap() {
            // Given
            Map<String, Object> emptyMap = new HashMap<>();

            // When
            AgentContext context = new TestAgentContext.Builder()
                    .properties(emptyMap)
                    .build();

            // Then
            assertThat(context.getProperties()).isEmpty();
        }

        @Test
        @DisplayName("should return immutable-like properties map")
        void shouldReturnPropertiesMap() {
            // Given
            AgentContext context = new TestAgentContext.Builder()
                    .property("key", "value")
                    .build();

            // When
            Map<String, Object> properties = context.getProperties();

            // Then
            assertThat(properties)
                    .isNotNull()
                    .containsEntry("key", "value");
        }
    }

    // Test implementation of AgentContext for testing purposes
    private static class TestAgentContext extends AgentContext {
        protected TestAgentContext(Builder builder) {
            super(builder);
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder extends AgentContext.Builder<Builder> {
            private String contextId;
            private String sessionId;
            private Instant createdAt;
            private Instant lastUpdated;

            public Builder() {
                agentDomain("test");
                contextType("test-context");
            }

            public Builder contextId(String contextId) {
                this.contextId = contextId;
                return self();
            }

            public Builder sessionId(String sessionId) {
                this.sessionId = sessionId;
                return self();
            }

            public Builder createdAt(Instant createdAt) {
                this.createdAt = createdAt;
                return self();
            }

            public Builder lastUpdated(Instant lastUpdated) {
                this.lastUpdated = lastUpdated;
                return self();
            }

            @Override
            protected Builder self() {
                return this;
            }

            public TestAgentContext build() {
                // Use reflection to set private fields in parent builder
                try {
                    java.lang.reflect.Field contextIdField = AgentContext.Builder.class.getDeclaredField("contextId");
                    contextIdField.setAccessible(true);
                    contextIdField.set(this, this.contextId);

                    java.lang.reflect.Field sessionIdField = AgentContext.Builder.class.getDeclaredField("sessionId");
                    sessionIdField.setAccessible(true);
                    sessionIdField.set(this, this.sessionId);

                    java.lang.reflect.Field createdAtField = AgentContext.Builder.class.getDeclaredField("createdAt");
                    createdAtField.setAccessible(true);
                    createdAtField.set(this, this.createdAt);

                    java.lang.reflect.Field lastUpdatedField = AgentContext.Builder.class
                            .getDeclaredField("lastUpdated");
                    lastUpdatedField.setAccessible(true);
                    lastUpdatedField.set(this, this.lastUpdated);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to set builder fields", e);
                }

                return new TestAgentContext(this);
            }
        }
    }
}
