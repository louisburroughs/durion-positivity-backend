package com.positivity.agent.impl;

import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.AgentPerformanceSpec;
import com.positivity.agent.BaseAgent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Configuration Management Agent - Specialized expertise for application
 * configuration
 * Provides guidance on centralized config, feature flags, and secrets
 * management
 * 
 * Validates: Requirements REQ-014.1, REQ-014.2, REQ-014.3, REQ-014.4, REQ-014.5
 */
@Component
public class ConfigurationManagementAgent extends BaseAgent {

  public ConfigurationManagementAgent() {
    super(
        "configuration-management-agent",
        "Configuration Management Agent",
        "configuration",
        Set.of("spring-cloud-config", "consul", "etcd", "feature-flags", "secrets-management",
            "aws-secrets-manager", "hashicorp-vault", "kubernetes-secrets", "config-validation",
            "environment-configs", "config-drift-detection"),
        Set.of("security-agent"), // Depends on security guidance for secrets
        AgentPerformanceSpec.defaultSpec());
  }

  @Override
  protected AgentGuidanceResponse processGuidanceRequest(AgentConsultationRequest request) {
    String guidance = generateConfigurationGuidance(request);
    List<String> recommendations = generateConfigurationRecommendations(request);

    return AgentGuidanceResponse.success(
        request.requestId(),
        getId(),
        guidance,
        0.90, // High confidence for configuration guidance
        recommendations,
        Duration.ofMillis(170));
  }

  private String generateConfigurationGuidance(AgentConsultationRequest request) {
    String baseGuidance = "Configuration Management Guidance for " + request.domain() + ":\n\n";
    String query = request.query().toLowerCase();

    // Application Configuration Management (REQ-014.1)
    if (query.contains("spring cloud config") || query.contains("consul") ||
        query.contains("etcd") || query.contains("centralized config")) {
      baseGuidance += generateCentralizedConfigGuidance(request);
    }

    // Feature Flags Implementation (REQ-014.2)
    else if (query.contains("feature flag") || query.contains("feature toggle") ||
        query.contains("gradual rollout") || query.contains("canary release")) {
      baseGuidance += generateFeatureFlagsGuidance(request);
    }

    // Secrets Management (REQ-014.3)
    else if (query.contains("secrets") || query.contains("aws secrets manager") ||
        query.contains("vault") || query.contains("kubernetes secrets")) {
      baseGuidance += generateSecretsManagementGuidance(request);
    }

    // Environment Configuration (REQ-014.4)
    else if (query.contains("environment") || query.contains("dev") ||
        query.contains("staging") || query.contains("production")) {
      baseGuidance += generateEnvironmentConfigGuidance(request);
    }

    // Configuration Validation (REQ-014.5)
    else if (query.contains("validation") || query.contains("schema") ||
        query.contains("drift detection") || query.contains("config validation")) {
      baseGuidance += generateConfigValidationGuidance(request);
    }

    // General configuration guidance
    else {
      baseGuidance += generateGeneralConfigurationGuidance(request);
    }

    return baseGuidance;
  }

  private String generateCentralizedConfigGuidance(AgentConsultationRequest request) {
    String query = request.query().toLowerCase();

    if (query.contains("spring cloud config")) {
      return generateSpringCloudConfigGuidance();
    } else if (query.contains("consul")) {
      return generateConsulConfigGuidance();
    } else if (query.contains("etcd")) {
      return generateEtcdConfigGuidance();
    } else {
      return generateGeneralCentralizedConfigGuidance();
    }
  }

  private String generateSpringCloudConfigGuidance() {
    return """
        Spring Cloud Config Server Setup:

        1. Config Server Configuration:
           ```yaml
           # application.yml (Config Server)
           server:
             port: 8888

           spring:
             cloud:
               config:
                 server:
                   git:
                     uri: https://github.com/your-org/pos-config-repo
                     default-label: main
                     search-paths: '{application}'
                   encrypt:
                     enabled: true
             security:
               user:
                 name: configuser
                 password: ${CONFIG_SERVER_PASSWORD}

           encrypt:
             key: ${CONFIG_ENCRYPT_KEY}
           ```

        2. Client Configuration:
           ```yaml
           # bootstrap.yml (Client Services)
           spring:
             application:
               name: pos-catalog
             cloud:
               config:
                 uri: http://config-server:8888
                 username: configuser
                 password: ${CONFIG_SERVER_PASSWORD}
                 fail-fast: true
                 retry:
                   initial-interval: 1000
                   max-attempts: 6
             profiles:
               active: ${SPRING_PROFILES_ACTIVE:dev}
           ```

        3. Configuration Repository Structure:
           ```
           config-repo/
           ├── pos-catalog/
           │   ├── pos-catalog.yml          # Default properties
           │   ├── pos-catalog-dev.yml      # Development environment
           │   ├── pos-catalog-staging.yml  # Staging environment
           │   └── pos-catalog-prod.yml     # Production environment
           ├── pos-order/
           │   ├── pos-order.yml
           │   └── pos-order-prod.yml
           └── application.yml               # Shared properties
           ```

        4. Encrypted Properties:
           ```yaml
           # Encrypt sensitive values
           spring:
             datasource:
               password: '{cipher}AQA...'  # Encrypted password

           # Use config server encryption endpoint
           curl -X POST http://config-server:8888/encrypt -d "mysecretpassword"
           ```

        5. Refresh Configuration:
           ```java
           @RefreshScope
           @RestController
           public class ConfigurableController {
               @Value("${app.message:Default}")
               private String message;

               @GetMapping("/message")
               public String getMessage() {
                   return message;
               }
           }

           # Refresh endpoint
           curl -X POST http://service:8080/actuator/refresh
           ```
        """;
  }

  private String generateConsulConfigGuidance() {
    return """
        Consul Configuration Management:

        1. Consul Server Setup:
           ```yaml
           # docker-compose.yml
           version: '3.8'
           services:
             consul:
               image: consul:1.15
               ports:
                 - "8500:8500"
               environment:
                 - CONSUL_BIND_INTERFACE=eth0
               command: >
                 consul agent -server -bootstrap-expect=1 -ui -client=0.0.0.0
           ```

        2. Spring Boot Integration:
           ```yaml
           # application.yml
           spring:
             cloud:
               consul:
                 host: consul
                 port: 8500
                 config:
                   enabled: true
                   format: YAML
                   data-key: data
                   watch:
                     enabled: true
                     delay: 1000
                 discovery:
                   enabled: true
                   health-check-path: /actuator/health
                   health-check-interval: 10s
           ```

        3. Configuration in Consul KV Store:
           ```bash
           # Store configuration in Consul
           consul kv put config/pos-catalog/data @pos-catalog-config.yml
           consul kv put config/pos-catalog,dev/data @pos-catalog-dev-config.yml
           consul kv put config/pos-catalog,prod/data @pos-catalog-prod-config.yml
           ```

        4. Dynamic Configuration Updates:
           ```java
           @Component
           @ConfigurationProperties(prefix = "app")
           @RefreshScope
           public class AppConfig {
               private String message;
               private int timeout;

               // Getters and setters
           }
           ```

        5. Service Discovery Integration:
           ```java
           @EnableDiscoveryClient
           @SpringBootApplication
           public class PosApplication {
               public static void main(String[] args) {
                   SpringApplication.run(PosApplication.class, args);
               }
           }
           ```
        """;
  }

  private String generateEtcdConfigGuidance() {
    return """
        etcd Configuration Management:

        1. etcd Cluster Setup:
           ```yaml
           # docker-compose.yml
           version: '3.8'
           services:
             etcd:
               image: quay.io/coreos/etcd:v3.5.9
               ports:
                 - "2379:2379"
                 - "2380:2380"
               environment:
                 - ETCD_NAME=etcd-server
                 - ETCD_DATA_DIR=/etcd-data
                 - ETCD_LISTEN_CLIENT_URLS=http://0.0.0.0:2379
                 - ETCD_ADVERTISE_CLIENT_URLS=http://etcd:2379
                 - ETCD_LISTEN_PEER_URLS=http://0.0.0.0:2380
                 - ETCD_INITIAL_ADVERTISE_PEER_URLS=http://etcd:2380
                 - ETCD_INITIAL_CLUSTER=etcd-server=http://etcd:2380
                 - ETCD_INITIAL_CLUSTER_TOKEN=etcd-cluster
                 - ETCD_INITIAL_CLUSTER_STATE=new
           ```

        2. Spring Boot etcd Integration:
           ```xml
           <dependency>
               <groupId>io.etcd</groupId>
               <artifactId>jetcd-core</artifactId>
               <version>0.7.5</version>
           </dependency>
           ```

        3. Configuration Service:
           ```java
           @Service
           public class EtcdConfigService {

               private final Client etcdClient;

               public EtcdConfigService() {
                   this.etcdClient = Client.builder()
                       .endpoints("http://etcd:2379")
                       .build();
               }

               public String getConfig(String key) {
                   try {
                       GetResponse response = etcdClient.getKVClient()
                           .get(ByteSequence.from(key, StandardCharsets.UTF_8))
                           .get();

                       if (response.getKvs().isEmpty()) {
                           return null;
                       }

                       return response.getKvs().get(0).getValue().toString(StandardCharsets.UTF_8);
                   } catch (Exception e) {
                       throw new RuntimeException("Failed to get config: " + key, e);
                   }
               }

               public void watchConfig(String keyPrefix, Consumer<String> callback) {
                   etcdClient.getWatchClient().watch(
                       ByteSequence.from(keyPrefix, StandardCharsets.UTF_8),
                       watchResponse -> {
                           watchResponse.getEvents().forEach(event -> {
                               String value = event.getKeyValue().getValue().toString(StandardCharsets.UTF_8);
                               callback.accept(value);
                           });
                       }
                   );
               }
           }
           ```

        4. Configuration Management:
           ```bash
           # Store configuration in etcd
           etcdctl put /config/pos-catalog/database.url "jdbc:postgresql://db:5432/catalog"
           etcdctl put /config/pos-catalog/database.username "catalog_user"
           etcdctl put /config/pos-catalog/cache.ttl "3600"

           # Watch for changes
           etcdctl watch --prefix /config/pos-catalog/
           ```
        """;
  }

  private String generateFeatureFlagsGuidance(AgentConsultationRequest request) {
    return """
        Feature Flags Implementation:

        1. Spring Boot Feature Flag Configuration:
           ```java
           @Component
           @ConfigurationProperties(prefix = "features")
           public class FeatureFlags {
               private boolean newCheckoutFlow = false;
               private boolean enhancedSearch = false;
               private boolean loyaltyProgram = false;
               private Map<String, Boolean> userSpecific = new HashMap<>();

               // Getters and setters
           }
           ```

        2. Feature Flag Service:
           ```java
           @Service
           public class FeatureFlagService {

               private final FeatureFlags featureFlags;
               private final RedisTemplate<String, Object> redisTemplate;

               public boolean isFeatureEnabled(String featureName) {
                   return isFeatureEnabled(featureName, null);
               }

               public boolean isFeatureEnabled(String featureName, String userId) {
                   // Check user-specific flags first
                   if (userId != null) {
                       String userKey = "feature:" + featureName + ":user:" + userId;
                       Boolean userFlag = (Boolean) redisTemplate.opsForValue().get(userKey);
                       if (userFlag != null) {
                           return userFlag;
                       }
                   }

                   // Check percentage rollout
                   String percentageKey = "feature:" + featureName + ":percentage";
                   Integer percentage = (Integer) redisTemplate.opsForValue().get(percentageKey);
                   if (percentage != null && userId != null) {
                       int userHash = Math.abs(userId.hashCode() % 100);
                       if (userHash < percentage) {
                           return true;
                       }
                   }

                   // Fall back to global flag
                   return getGlobalFeatureFlag(featureName);
               }

               private boolean getGlobalFeatureFlag(String featureName) {
                   switch (featureName) {
                       case "newCheckoutFlow": return featureFlags.isNewCheckoutFlow();
                       case "enhancedSearch": return featureFlags.isEnhancedSearch();
                       case "loyaltyProgram": return featureFlags.isLoyaltyProgram();
                       default: return false;
                   }
               }
           }
           ```

        3. Feature Flag Usage:
           ```java
           @RestController
           public class CheckoutController {

               private final FeatureFlagService featureFlagService;
               private final CheckoutService checkoutService;
               private final NewCheckoutService newCheckoutService;

               @PostMapping("/checkout")
               public ResponseEntity<CheckoutResponse> checkout(
                       @RequestBody CheckoutRequest request,
                       @RequestHeader("User-ID") String userId) {

                   if (featureFlagService.isFeatureEnabled("newCheckoutFlow", userId)) {
                       return ResponseEntity.ok(newCheckoutService.processCheckout(request));
                   } else {
                       return ResponseEntity.ok(checkoutService.processCheckout(request));
                   }
               }
           }
           ```

        4. Gradual Rollout Strategy:
           ```yaml
           # Configuration for gradual rollout
           features:
             new-checkout-flow:
               enabled: true
               rollout-percentage: 10  # Start with 10% of users
               target-groups:
                 - beta-users
                 - premium-customers
               exclude-groups:
                 - high-risk-users
           ```

        5. Feature Flag Management API:
           ```java
           @RestController
           @RequestMapping("/admin/features")
           public class FeatureFlagController {

               @PostMapping("/{featureName}/enable")
               public ResponseEntity<Void> enableFeature(
                       @PathVariable String featureName,
                       @RequestParam(required = false) Integer percentage,
                       @RequestParam(required = false) String userId) {

                   if (userId != null) {
                       redisTemplate.opsForValue().set("feature:" + featureName + ":user:" + userId, true);
                   } else if (percentage != null) {
                       redisTemplate.opsForValue().set("feature:" + featureName + ":percentage", percentage);
                   } else {
                       // Enable globally through config refresh
                       configRefreshService.updateFeatureFlag(featureName, true);
                   }

                   return ResponseEntity.ok().build();
               }
           }
           ```
        """;
  }

  private String generateSecretsManagementGuidance(AgentConsultationRequest request) {
    String query = request.query().toLowerCase();

    if (query.contains("aws secrets manager")) {
      return generateAWSSecretsManagerGuidance();
    } else if (query.contains("vault") || query.contains("hashicorp")) {
      return generateHashiCorpVaultGuidance();
    } else if (query.contains("kubernetes")) {
      return generateKubernetesSecretsGuidance();
    } else {
      return generateGeneralSecretsGuidance();
    }
  }

  private String generateAWSSecretsManagerGuidance() {
    return """
        AWS Secrets Manager Integration:

        1. Spring Boot Configuration:
           ```xml
           <dependency>
               <groupId>org.springframework.cloud</groupId>
               <artifactId>spring-cloud-starter-aws-secrets-manager-config</artifactId>
           </dependency>
           ```

        2. Application Configuration:
           ```yaml
           # bootstrap.yml
           aws:
             secretsmanager:
               enabled: true
               prefix: /secret
               default-context: pos-application
               profile-separator: _
               fail-fast: true
               region: us-east-1

           spring:
             application:
               name: pos-catalog
           ```

        3. Secrets Structure in AWS:
           ```json
           // Secret: /secret/pos-catalog
           {
             "spring.datasource.password": "encrypted_db_password",
             "spring.redis.password": "encrypted_redis_password",
             "jwt.secret": "encrypted_jwt_secret",
             "external.api.key": "encrypted_api_key"
           }

           // Secret: /secret/pos-catalog_prod
           {
             "spring.datasource.url": "jdbc:postgresql://prod-db:5432/catalog",
             "spring.datasource.username": "prod_catalog_user"
           }
           ```

        4. IAM Policy for Secrets Access:
           ```json
           {
             "Version": "2012-10-17",
             "Statement": [
               {
                 "Effect": "Allow",
                 "Action": [
                   "secretsmanager:GetSecretValue",
                   "secretsmanager:DescribeSecret"
                 ],
                 "Resource": [
                   "arn:aws:secretsmanager:us-east-1:123456789012:secret:/secret/pos-*"
                 ]
               }
             ]
           }
           ```

        5. Secret Rotation Configuration:
           ```java
           @Configuration
           public class SecretsConfig {

               @EventListener
               public void handleSecretRefresh(RefreshRemoteApplicationEvent event) {
                   log.info("Secrets refreshed for keys: {}", event.getDestination());
                   // Trigger connection pool refresh, cache clear, etc.
               }
           }
           ```
        """;
  }

  private String generateHashiCorpVaultGuidance() {
    return """
        HashiCorp Vault Integration:

        1. Vault Server Setup:
           ```yaml
           # docker-compose.yml
           version: '3.8'
           services:
             vault:
               image: vault:1.14
               ports:
                 - "8200:8200"
               environment:
                 - VAULT_DEV_ROOT_TOKEN_ID=myroot
                 - VAULT_DEV_LISTEN_ADDRESS=0.0.0.0:8200
               cap_add:
                 - IPC_LOCK
           ```

        2. Spring Boot Vault Configuration:
           ```xml
           <dependency>
               <groupId>org.springframework.cloud</groupId>
               <artifactId>spring-cloud-starter-vault-config</artifactId>
           </dependency>
           ```

        3. Application Configuration:
           ```yaml
           # bootstrap.yml
           spring:
             cloud:
               vault:
                 uri: http://vault:8200
                 authentication: TOKEN
                 token: ${VAULT_TOKEN}
                 kv:
                   enabled: true
                   backend: secret
                   profile-separator: '/'
                   default-context: pos-catalog
                 database:
                   enabled: true
                   role: pos-catalog-role
                   backend: database
           ```

        4. Vault Policies and Roles:
           ```hcl
           # Policy for pos-catalog service
           path "secret/data/pos-catalog/*" {
             capabilities = ["read"]
           }

           path "database/creds/pos-catalog-role" {
             capabilities = ["read"]
           }

           # Database role configuration
           vault write database/roles/pos-catalog-role \
               db_name=postgres \
               creation_statements="CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO \"{{name}}\";" \
               default_ttl="1h" \
               max_ttl="24h"
           ```

        5. Dynamic Secrets Usage:
           ```java
           @Service
           public class DatabaseService {

               @Value("${spring.datasource.username}")
               private String username;

               @Value("${spring.datasource.password}")
               private String password;

               @EventListener
               public void handleVaultTokenRenewal(VaultTokenRenewalEvent event) {
                   // Refresh database connections with new credentials
                   refreshDataSource();
               }
           }
           ```
        """;
  }

  private String generateKubernetesSecretsGuidance() {
    return """
        Kubernetes Secrets Management:

        1. Secret Creation and Management:
           ```yaml
           # Database credentials secret
           apiVersion: v1
           kind: Secret
           metadata:
             name: pos-catalog-db-secret
             namespace: pos-system
           type: Opaque
           data:
             username: Y2F0YWxvZ191c2Vy  # base64 encoded
             password: c2VjcmV0cGFzcw==  # base64 encoded

           ---
           # TLS certificate secret
           apiVersion: v1
           kind: Secret
           metadata:
             name: pos-tls-secret
           type: kubernetes.io/tls
           data:
             tls.crt: LS0tLS1CRUdJTi...
             tls.key: LS0tLS1CRUdJTi...
           ```

        2. Using Secrets in Deployments:
           ```yaml
           apiVersion: apps/v1
           kind: Deployment
           metadata:
             name: pos-catalog
           spec:
             template:
               spec:
                 containers:
                 - name: pos-catalog
                   image: pos-catalog:latest
                   env:
                   - name: DB_USERNAME
                     valueFrom:
                       secretKeyRef:
                         name: pos-catalog-db-secret
                         key: username
                   - name: DB_PASSWORD
                     valueFrom:
                       secretKeyRef:
                         name: pos-catalog-db-secret
                         key: password
                   volumeMounts:
                   - name: tls-certs
                     mountPath: /etc/ssl/certs
                     readOnly: true
                 volumes:
                 - name: tls-certs
                   secret:
                     secretName: pos-tls-secret
           ```

        3. External Secrets Operator Integration:
           ```yaml
           # Install External Secrets Operator
           apiVersion: external-secrets.io/v1beta1
           kind: SecretStore
           metadata:
             name: aws-secrets-manager
           spec:
             provider:
               aws:
                 service: SecretsManager
                 region: us-east-1
                 auth:
                   jwt:
                     serviceAccountRef:
                       name: external-secrets-sa

           ---
           apiVersion: external-secrets.io/v1beta1
           kind: ExternalSecret
           metadata:
             name: pos-catalog-secrets
           spec:
             refreshInterval: 15s
             secretStoreRef:
               name: aws-secrets-manager
               kind: SecretStore
             target:
               name: pos-catalog-db-secret
               creationPolicy: Owner
             data:
             - secretKey: username
               remoteRef:
                 key: pos-catalog-db
                 property: username
             - secretKey: password
               remoteRef:
                 key: pos-catalog-db
                 property: password
           ```

        4. Sealed Secrets for GitOps:
           ```bash
           # Install Sealed Secrets controller
           kubectl apply -f https://github.com/bitnami-labs/sealed-secrets/releases/download/v0.18.0/controller.yaml

           # Create sealed secret
           echo -n mypassword | kubectl create secret generic mysecret --dry-run=client --from-file=password=/dev/stdin -o yaml | kubeseal -o yaml > mysealedsecret.yaml
           ```

        5. Secret Rotation and Monitoring:
           ```yaml
           # CronJob for secret rotation
           apiVersion: batch/v1
           kind: CronJob
           metadata:
             name: secret-rotation
           spec:
             schedule: "0 2 * * 0"  # Weekly on Sunday at 2 AM
             jobTemplate:
               spec:
                 template:
                   spec:
                     containers:
                     - name: secret-rotator
                       image: secret-rotator:latest
                       command:
                       - /bin/sh
                       - -c
                       - |
                         # Rotate database passwords
                         kubectl patch secret pos-catalog-db-secret -p='{"data":{"password":"'$(openssl rand -base64 32 | base64 -w 0)'"}}'
                         # Restart deployments to pick up new secrets
                         kubectl rollout restart deployment/pos-catalog
                     restartPolicy: OnFailure
           ```
        """;
  }

  private String generateEnvironmentConfigGuidance(AgentConsultationRequest request) {
    return """
        Environment-Specific Configuration Management:

        1. Spring Profiles Configuration:
           ```yaml
           # application.yml (base configuration)
           spring:
             application:
               name: pos-catalog
             jpa:
               hibernate:
                 ddl-auto: validate

           logging:
             level:
               com.positivity: INFO

           ---
           # application-dev.yml
           spring:
             config:
               activate:
                 on-profile: dev
             datasource:
               url: jdbc:h2:mem:testdb
               driver-class-name: org.h2.Driver
             jpa:
               hibernate:
                 ddl-auto: create-drop
               show-sql: true

           logging:
             level:
               com.positivity: DEBUG
               org.springframework: DEBUG

           ---
           # application-prod.yml
           spring:
             config:
               activate:
                 on-profile: prod
             datasource:
               url: ${DB_URL}
               username: ${DB_USERNAME}
               password: ${DB_PASSWORD}
               hikari:
                 maximum-pool-size: 20
                 minimum-idle: 5

           logging:
             level:
               com.positivity: WARN
               org.springframework: WARN
           ```

        2. Environment Variable Configuration:
           ```bash
           # Development environment
           export SPRING_PROFILES_ACTIVE=dev
           export LOG_LEVEL=DEBUG
           export DB_URL=jdbc:postgresql://localhost:5432/pos_dev

           # Staging environment
           export SPRING_PROFILES_ACTIVE=staging
           export LOG_LEVEL=INFO
           export DB_URL=jdbc:postgresql://staging-db:5432/pos_staging
           export REDIS_URL=redis://staging-redis:6379

           # Production environment
           export SPRING_PROFILES_ACTIVE=prod
           export LOG_LEVEL=WARN
           export DB_URL=jdbc:postgresql://prod-db:5432/pos_prod
           export REDIS_URL=redis://prod-redis:6379
           export JVM_OPTS="-Xmx2g -Xms1g"
           ```

        3. Docker Environment Configuration:
           ```dockerfile
           # Multi-stage Dockerfile with environment support
           FROM openjdk:21-jre-slim AS base
           WORKDIR /app
           COPY target/*.jar app.jar

           FROM base AS dev
           ENV SPRING_PROFILES_ACTIVE=dev
           ENV JVM_OPTS="-Xmx512m"
           EXPOSE 8080
           CMD ["sh", "-c", "java $JVM_OPTS -jar app.jar"]

           FROM base AS prod
           ENV SPRING_PROFILES_ACTIVE=prod
           ENV JVM_OPTS="-Xmx2g -XX:+UseG1GC"
           EXPOSE 8080
           CMD ["sh", "-c", "java $JVM_OPTS -jar app.jar"]
           ```

        4. Kubernetes ConfigMap per Environment:
           ```yaml
           # Development ConfigMap
           apiVersion: v1
           kind: ConfigMap
           metadata:
             name: pos-catalog-config-dev
             namespace: pos-dev
           data:
             application.yml: |
               spring:
                 profiles:
                   active: dev
               logging:
                 level:
                   com.positivity: DEBUG

           ---
           # Production ConfigMap
           apiVersion: v1
           kind: ConfigMap
           metadata:
             name: pos-catalog-config-prod
             namespace: pos-prod
           data:
             application.yml: |
               spring:
                 profiles:
                   active: prod
               logging:
                 level:
                   com.positivity: WARN
           ```

        5. Environment Isolation Strategy:
           - Separate namespaces for each environment
           - Environment-specific service accounts and RBAC
           - Network policies for environment isolation
           - Separate monitoring and logging per environment
        """;
  }

  private String generateConfigValidationGuidance(AgentConsultationRequest request) {
    return """
        Configuration Validation and Drift Detection:

        1. Configuration Schema Validation:
           ```java
           @Configuration
           @ConfigurationProperties(prefix = "app")
           @Validated
           public class AppConfig {

               @NotBlank
               @Pattern(regexp = "^https?://.*", message = "Must be a valid URL")
               private String apiUrl;

               @Min(1)
               @Max(3600)
               private int timeoutSeconds = 30;

               @Valid
               private DatabaseConfig database;

               @Valid
               private List<@Valid ServiceConfig> services;

               // Getters and setters
           }

           @Data
           public static class DatabaseConfig {
               @NotBlank
               private String url;

               @Min(1)
               @Max(100)
               private int maxConnections = 10;
           }
           ```

        2. Configuration Validation Service:
           ```java
           @Service
           public class ConfigValidationService {

               private final Validator validator;

               @EventListener
               public void validateConfigOnRefresh(RefreshRemoteApplicationEvent event) {
                   validateAllConfigurations();
               }

               public void validateAllConfigurations() {
                   List<String> errors = new ArrayList<>();

                   // Validate database connections
                   errors.addAll(validateDatabaseConnections());

                   // Validate external service endpoints
                   errors.addAll(validateExternalServices());

                   // Validate feature flag consistency
                   errors.addAll(validateFeatureFlags());

                   if (!errors.isEmpty()) {
                       log.error("Configuration validation failed: {}", errors);
                       // Send alerts or fail health check
                       throw new ConfigurationException("Invalid configuration: " + String.join(", ", errors));
                   }
               }

               private List<String> validateDatabaseConnections() {
                   List<String> errors = new ArrayList<>();
                   try {
                       dataSource.getConnection().close();
                   } catch (SQLException e) {
                       errors.add("Database connection failed: " + e.getMessage());
                   }
                   return errors;
               }
           }
           ```

        3. Configuration Drift Detection:
           ```java
           @Component
           public class ConfigDriftDetector {

               private final Map<String, String> baselineConfig = new HashMap<>();

               @PostConstruct
               public void captureBaseline() {
                   captureCurrentConfiguration();
               }

               @Scheduled(fixedRate = 300000) // Every 5 minutes
               public void detectDrift() {
                   Map<String, String> currentConfig = getCurrentConfiguration();
                   List<ConfigDrift> drifts = new ArrayList<>();

                   for (Map.Entry<String, String> entry : baselineConfig.entrySet()) {
                       String key = entry.getKey();
                       String baselineValue = entry.getValue();
                       String currentValue = currentConfig.get(key);

                       if (!Objects.equals(baselineValue, currentValue)) {
                           drifts.add(new ConfigDrift(key, baselineValue, currentValue));
                       }
                   }

                   if (!drifts.isEmpty()) {
                       handleConfigDrift(drifts);
                   }
               }

               private void handleConfigDrift(List<ConfigDrift> drifts) {
                   log.warn("Configuration drift detected: {}", drifts);
                   // Send alerts, update monitoring metrics
                   meterRegistry.counter("config.drift.detected").increment(drifts.size());
               }
           }
           ```

        4. Health Check Integration:
           ```java
           @Component
           public class ConfigurationHealthIndicator implements HealthIndicator {

               private final ConfigValidationService validationService;

               @Override
               public Health health() {
                   try {
                       validationService.validateAllConfigurations();
                       return Health.up()
                           .withDetail("status", "All configurations valid")
                           .build();
                   } catch (ConfigurationException e) {
                       return Health.down()
                           .withDetail("error", e.getMessage())
                           .build();
                   }
               }
           }
           ```

        5. Configuration Monitoring and Alerting:
           ```yaml
           # Prometheus metrics for configuration
           management:
             endpoints:
               web:
                 exposure:
                   include: health,info,metrics,configprops
             metrics:
               export:
                 prometheus:
                   enabled: true
               tags:
                 application: ${spring.application.name}
                 environment: ${spring.profiles.active}
           ```
        """;
  }

  private String generateGeneralCentralizedConfigGuidance() {
    return """
        General Centralized Configuration Best Practices:

        1. Configuration Hierarchy:
           - Global defaults (shared across all services)
           - Service-specific configurations
           - Environment-specific overrides
           - Runtime dynamic configurations

        2. Configuration Security:
           - Encrypt sensitive configuration values
           - Use proper access controls and authentication
           - Audit configuration changes
           - Implement configuration validation

        3. Configuration Management:
           - Version control for configuration changes
           - Automated configuration deployment
           - Configuration rollback capabilities
           - Configuration drift detection and alerting
        """;
  }

  private String generateGeneralSecretsGuidance() {
    return """
        General Secrets Management Best Practices:

        1. Secret Storage Principles:
           - Never store secrets in code or configuration files
           - Use dedicated secret management systems
           - Implement proper access controls and audit trails
           - Rotate secrets regularly

        2. Secret Access Patterns:
           - Use service accounts and IAM roles
           - Implement least privilege access
           - Use short-lived tokens when possible
           - Monitor secret access and usage

        3. Secret Rotation Strategy:
           - Automated secret rotation
           - Zero-downtime secret updates
           - Fallback mechanisms during rotation
           - Validation of new secrets before activation
        """;
  }

  private String generateGeneralConfigurationGuidance(AgentConsultationRequest request) {
    return """
        Comprehensive Configuration Management Strategy:

        1. Configuration Architecture:
           - Centralized configuration server for shared settings
           - Service-specific configuration for business logic
           - Environment-specific overrides for deployment settings
           - Runtime configuration for feature flags and toggles

        2. Configuration Security:
           - Encrypt sensitive configuration data
           - Use proper authentication and authorization
           - Implement configuration audit trails
           - Regular security reviews of configuration access

        3. Configuration Operations:
           - Automated configuration deployment
           - Configuration validation and testing
           - Rollback capabilities for configuration changes
           - Monitoring and alerting for configuration issues

        4. Best Practices:
           - Use Infrastructure as Code for configuration management
           - Implement configuration versioning and change tracking
           - Provide configuration documentation and examples
           - Regular configuration cleanup and optimization
        """;
  }

  private List<String> generateConfigurationRecommendations(AgentConsultationRequest request) {
    String query = request.query().toLowerCase();

    if (query.contains("spring cloud config") || query.contains("centralized")) {
      return List.of(
          "Implement Spring Cloud Config Server with Git backend",
          "Use encryption for sensitive configuration values",
          "Configure automatic configuration refresh with @RefreshScope",
          "Implement configuration validation and health checks");
    } else if (query.contains("feature flag") || query.contains("toggle")) {
      return List.of(
          "Implement gradual rollout strategy with percentage-based flags",
          "Use Redis or database for dynamic feature flag storage",
          "Create feature flag management API for runtime control",
          "Implement feature flag monitoring and analytics");
    } else if (query.contains("secrets") || query.contains("vault")) {
      return List.of(
          "Use dedicated secret management systems (Vault, AWS Secrets Manager)",
          "Implement automatic secret rotation with zero downtime",
          "Use IAM roles and service accounts for secret access",
          "Monitor secret access and implement audit trails");
    } else if (query.contains("environment") || query.contains("profile")) {
      return List.of(
          "Use Spring profiles for environment-specific configuration",
          "Implement proper environment isolation and security",
          "Use ConfigMaps and Secrets in Kubernetes deployments",
          "Implement configuration validation per environment");
    } else {
      return List.of(
          "Implement centralized configuration management for consistency",
          "Use proper secret management and encryption",
          "Implement configuration validation and drift detection",
          "Use feature flags for safe production deployments",
          "Monitor configuration changes and implement audit trails");
    }
  }
}