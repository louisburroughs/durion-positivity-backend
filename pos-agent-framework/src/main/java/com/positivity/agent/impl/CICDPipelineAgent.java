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
 * CI/CD Pipeline Agent - Specialized expertise for continuous integration and
 * deployment
 * Provides guidance on build automation, testing pipelines, and deployment
 * strategies
 * 
 * Validates: Requirements REQ-013.1, REQ-013.2, REQ-013.3, REQ-013.4, REQ-013.5
 */
@Component
public class CICDPipelineAgent extends BaseAgent {

  public CICDPipelineAgent() {
    super(
        "cicd-pipeline-agent",
        "CI/CD Pipeline Agent",
        "cicd",
        Set.of("build-automation", "maven", "gradle", "docker", "testing-pipelines",
            "deployment-strategies", "security-scanning", "jenkins", "github-actions",
            "gitlab-ci", "blue-green", "canary", "rolling-deployment"),
        Set.of("security-agent", "testing-agent"), // Depends on security and testing guidance
        AgentPerformanceSpec.defaultSpec());
  }

  @Override
  protected AgentGuidanceResponse processGuidanceRequest(AgentConsultationRequest request) {
    String guidance = generateCICDGuidance(request);
    List<String> recommendations = generateCICDRecommendations(request);

    return AgentGuidanceResponse.success(
        request.requestId(),
        getId(),
        guidance,
        0.91, // High confidence for CI/CD guidance
        recommendations,
        Duration.ofMillis(200));
  }

  private String generateCICDGuidance(AgentConsultationRequest request) {
    String baseGuidance = "CI/CD Pipeline Guidance for " + request.domain() + ":\n\n";
    String query = request.query().toLowerCase();

    // Build Pipeline Configuration (REQ-013.1)
    if (query.contains("build") || query.contains("maven") || query.contains("gradle") ||
        query.contains("docker build")) {
      baseGuidance += generateBuildPipelineGuidance(request);
    }

    // Testing Pipeline Automation (REQ-013.2)
    else if (query.contains("testing pipeline") || query.contains("test automation") ||
        query.contains("unit test") || query.contains("integration test")) {
      baseGuidance += generateTestingPipelineGuidance(request);
    }

    // Deployment Strategies (REQ-013.3)
    else if (query.contains("deployment") || query.contains("blue-green") ||
        query.contains("canary") || query.contains("rolling")) {
      baseGuidance += generateDeploymentStrategyGuidance(request);
    }

    // Security Scanning (REQ-013.4)
    else if (query.contains("security scan") || query.contains("sast") ||
        query.contains("dast") || query.contains("vulnerability")) {
      baseGuidance += generateSecurityScanningGuidance(request);
    }

    // Pipeline Orchestration (REQ-013.5)
    else if (query.contains("jenkins") || query.contains("github actions") ||
        query.contains("gitlab ci") || query.contains("pipeline orchestration")) {
      baseGuidance += generatePipelineOrchestrationGuidance(request);
    }

    // General CI/CD guidance
    else {
      baseGuidance += generateGeneralCICDGuidance(request);
    }

    return baseGuidance;
  }

  private String generateBuildPipelineGuidance(AgentConsultationRequest request) {
    String query = request.query().toLowerCase();

    if (query.contains("maven")) {
      return generateMavenBuildGuidance();
    } else if (query.contains("gradle")) {
      return generateGradleBuildGuidance();
    } else if (query.contains("docker")) {
      return generateDockerBuildGuidance();
    } else {
      return generateGeneralBuildGuidance();
    }
  }

  private String generateMavenBuildGuidance() {
    return """
        Maven Build Pipeline Configuration:

        1. Multi-Module Maven Setup:
           ```xml
           <project>
               <groupId>com.positivity</groupId>
               <artifactId>pos-parent</artifactId>
               <packaging>pom</packaging>
               <modules>
                   <module>pos-agent-framework</module>
                   <module>pos-catalog</module>
                   <module>pos-order</module>
               </modules>
           </project>
           ```

        2. Build Profiles for Different Environments:
           ```xml
           <profiles>
               <profile>
                   <id>dev</id>
                   <properties>
                       <spring.profiles.active>dev</spring.profiles.active>
                       <maven.test.skip>false</maven.test.skip>
                   </properties>
               </profile>
               <profile>
                   <id>prod</id>
                   <properties>
                       <spring.profiles.active>prod</spring.profiles.active>
                       <maven.test.skip>false</maven.test.skip>
                   </properties>
               </profile>
           </profiles>
           ```

        3. Pipeline Build Commands:
           ```bash
           # Clean and compile
           mvn clean compile

           # Run tests with coverage
           mvn test jacoco:report

           # Package with specific profile
           mvn package -Pprod -DskipTests=false

           # Build Docker images
           mvn spring-boot:build-image -Dspring-boot.build-image.imageName=pos-service:latest
           ```

        4. Dependency Management:
           - Use dependencyManagement for version consistency
           - Implement Maven Enforcer Plugin for dependency conflicts
           - Use Maven Versions Plugin for dependency updates
        """;
  }

  private String generateGradleBuildGuidance() {
    return """
        Gradle Build Pipeline Configuration:

        1. Multi-Project Gradle Setup:
           ```gradle
           // settings.gradle
           rootProject.name = 'positivity-pos'
           include 'pos-agent-framework'
           include 'pos-catalog'
           include 'pos-order'

           // build.gradle (root)
           subprojects {
               apply plugin: 'java'
               apply plugin: 'org.springframework.boot'

               dependencies {
                   implementation 'org.springframework.boot:spring-boot-starter'
                   testImplementation 'org.springframework.boot:spring-boot-starter-test'
               }
           }
           ```

        2. Build Tasks and Profiles:
           ```gradle
           tasks.register('buildAll') {
               dependsOn subprojects.collect { it.tasks.named('build') }
           }

           tasks.register('testAll') {
               dependsOn subprojects.collect { it.tasks.named('test') }
           }

           // Environment-specific builds
           if (project.hasProperty('env') && project.env == 'prod') {
               bootJar {
                   archiveClassifier = 'prod'
               }
           }
           ```

        3. Pipeline Build Commands:
           ```bash
           # Clean and build all modules
           ./gradlew clean buildAll

           # Run tests with coverage
           ./gradlew testAll jacocoTestReport

           # Build production artifacts
           ./gradlew bootJar -Penv=prod

           # Build Docker images
           ./gradlew bootBuildImage --imageName=pos-service:latest
           ```
        """;
  }

  private String generateDockerBuildGuidance() {
    return """
        Docker Build Pipeline Configuration:

        1. Multi-Stage Dockerfile for Spring Boot:
           ```dockerfile
           # Build stage
           FROM openjdk:21-jdk-slim AS builder
           WORKDIR /app
           COPY pom.xml .
           COPY src ./src
           RUN ./mvnw clean package -DskipTests

           # Runtime stage
           FROM openjdk:21-jre-slim
           WORKDIR /app
           COPY --from=builder /app/target/*.jar app.jar
           EXPOSE 8080
           ENTRYPOINT ["java", "-jar", "app.jar"]
           ```

        2. Docker Compose for Development:
           ```yaml
           version: '3.8'
           services:
             pos-catalog:
               build: ./pos-catalog
               ports:
                 - "8081:8080"
               environment:
                 - SPRING_PROFILES_ACTIVE=dev
               depends_on:
                 - postgres

             postgres:
               image: postgres:15
               environment:
                 POSTGRES_DB: posdb
                 POSTGRES_USER: posuser
                 POSTGRES_PASSWORD: pospass
           ```

        3. Build Pipeline Commands:
           ```bash
           # Build all service images
           docker-compose build

           # Build with cache optimization
           docker build --target builder -t pos-service:builder .
           docker build --cache-from pos-service:builder -t pos-service:latest .

           # Multi-architecture builds
           docker buildx build --platform linux/amd64,linux/arm64 -t pos-service:latest .
           ```

        4. Image Optimization:
           - Use distroless or alpine base images
           - Implement proper layer caching
           - Use .dockerignore to reduce build context
           - Implement health checks in Dockerfile
        """;
  }

  private String generateTestingPipelineGuidance(AgentConsultationRequest request) {
    return """
        Testing Pipeline Automation Configuration:

        1. Comprehensive Testing Strategy:
           ```yaml
           # GitHub Actions example
           name: Testing Pipeline
           on: [push, pull_request]

           jobs:
             test:
               runs-on: ubuntu-latest
               steps:
                 - uses: actions/checkout@v3
                 - uses: actions/setup-java@v3
                   with:
                     java-version: '21'

                 # Unit Tests
                 - name: Run Unit Tests
                   run: ./mvnw test

                 # Integration Tests
                 - name: Run Integration Tests
                   run: ./mvnw verify -Pintegration-tests

                 # Contract Tests
                 - name: Run Contract Tests
                   run: ./mvnw test -Pcontract-tests

                 # Security Tests
                 - name: Run Security Tests
                   run: ./mvnw verify -Psecurity-tests
           ```

        2. Test Configuration by Type:
           ```xml
           <!-- Maven Surefire for Unit Tests -->
           <plugin>
               <groupId>org.apache.maven.plugins</groupId>
               <artifactId>maven-surefire-plugin</artifactId>
               <configuration>
                   <includes>
                       <include>**/*Test.java</include>
                       <include>**/*Tests.java</include>
                   </includes>
                   <excludes>
                       <exclude>**/*IntegrationTest.java</exclude>
                   </excludes>
               </configuration>
           </plugin>

           <!-- Maven Failsafe for Integration Tests -->
           <plugin>
               <groupId>org.apache.maven.plugins</groupId>
               <artifactId>maven-failsafe-plugin</artifactId>
               <configuration>
                   <includes>
                       <include>**/*IntegrationTest.java</include>
                       <include>**/*IT.java</include>
                   </includes>
               </configuration>
           </plugin>
           ```

        3. TestContainers Integration:
           ```java
           @SpringBootTest
           @Testcontainers
           class OrderServiceIntegrationTest {

               @Container
               static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
                       .withDatabaseName("testdb")
                       .withUsername("test")
                       .withPassword("test");

               @DynamicPropertySource
               static void configureProperties(DynamicPropertyRegistry registry) {
                   registry.add("spring.datasource.url", postgres::getJdbcUrl);
                   registry.add("spring.datasource.username", postgres::getUsername);
                   registry.add("spring.datasource.password", postgres::getPassword);
               }
           }
           ```

        4. Test Reporting and Coverage:
           - JaCoCo for code coverage (minimum 80% threshold)
           - Surefire reports for test results
           - Allure for comprehensive test reporting
           - SonarQube integration for quality gates
        """;
  }

  private String generateDeploymentStrategyGuidance(AgentConsultationRequest request) {
    String query = request.query().toLowerCase();

    if (query.contains("blue-green")) {
      return generateBlueGreenDeploymentGuidance();
    } else if (query.contains("canary")) {
      return generateCanaryDeploymentGuidance();
    } else if (query.contains("rolling")) {
      return generateRollingDeploymentGuidance();
    } else {
      return generateGeneralDeploymentGuidance();
    }
  }

  private String generateBlueGreenDeploymentGuidance() {
    return """
        Blue-Green Deployment Strategy:

        1. Kubernetes Blue-Green Configuration:
           ```yaml
           # Blue deployment
           apiVersion: apps/v1
           kind: Deployment
           metadata:
             name: pos-service-blue
             labels:
               app: pos-service
               version: blue
           spec:
             replicas: 3
             selector:
               matchLabels:
                 app: pos-service
                 version: blue
             template:
               spec:
                 containers:
                 - name: pos-service
                   image: pos-service:v1.0.0

           ---
           # Service switching between blue/green
           apiVersion: v1
           kind: Service
           metadata:
             name: pos-service
           spec:
             selector:
               app: pos-service
               version: blue  # Switch to 'green' for deployment
             ports:
             - port: 80
               targetPort: 8080
           ```

        2. Deployment Pipeline Script:
           ```bash
           #!/bin/bash
           CURRENT_VERSION=$(kubectl get service pos-service -o jsonpath='{.spec.selector.version}')
           NEW_VERSION=$([ "$CURRENT_VERSION" = "blue" ] && echo "green" || echo "blue")

           # Deploy new version
           kubectl set image deployment/pos-service-$NEW_VERSION pos-service=pos-service:$IMAGE_TAG
           kubectl rollout status deployment/pos-service-$NEW_VERSION

           # Health check
           kubectl wait --for=condition=ready pod -l app=pos-service,version=$NEW_VERSION --timeout=300s

           # Switch traffic
           kubectl patch service pos-service -p '{"spec":{"selector":{"version":"'$NEW_VERSION'"}}}'

           # Cleanup old version (optional)
           kubectl scale deployment pos-service-$CURRENT_VERSION --replicas=0
           ```

        3. Rollback Strategy:
           ```bash
           # Quick rollback by switching service selector
           kubectl patch service pos-service -p '{"spec":{"selector":{"version":"'$PREVIOUS_VERSION'"}}}'
           ```
        """;
  }

  private String generateCanaryDeploymentGuidance() {
    return """
        Canary Deployment Strategy:

        1. Istio Canary Configuration:
           ```yaml
           apiVersion: networking.istio.io/v1beta1
           kind: VirtualService
           metadata:
             name: pos-service
           spec:
             http:
             - match:
               - headers:
                   canary:
                     exact: "true"
               route:
               - destination:
                   host: pos-service
                   subset: canary
             - route:
               - destination:
                   host: pos-service
                   subset: stable
                 weight: 90
               - destination:
                   host: pos-service
                   subset: canary
                 weight: 10

           ---
           apiVersion: networking.istio.io/v1beta1
           kind: DestinationRule
           metadata:
             name: pos-service
           spec:
             host: pos-service
             subsets:
             - name: stable
               labels:
                 version: stable
             - name: canary
               labels:
                 version: canary
           ```

        2. Gradual Traffic Shifting:
           ```bash
           # Start with 5% traffic to canary
           kubectl patch virtualservice pos-service --type='merge' -p='{"spec":{"http":[{"route":[{"destination":{"host":"pos-service","subset":"stable"},"weight":95},{"destination":{"host":"pos-service","subset":"canary"},"weight":5}]}]}}'

           # Monitor metrics and gradually increase
           # 10% -> 25% -> 50% -> 100%
           ```

        3. Automated Canary Analysis:
           ```yaml
           # Flagger canary configuration
           apiVersion: flagger.app/v1beta1
           kind: Canary
           metadata:
             name: pos-service
           spec:
             targetRef:
               apiVersion: apps/v1
               kind: Deployment
               name: pos-service
             service:
               port: 8080
             analysis:
               interval: 1m
               threshold: 5
               maxWeight: 50
               stepWeight: 10
               metrics:
               - name: request-success-rate
                 threshold: 99
               - name: request-duration
                 threshold: 500
           ```
        """;
  }

  private String generateSecurityScanningGuidance(AgentConsultationRequest request) {
    return """
        Security Scanning Pipeline Integration:

        1. SAST (Static Application Security Testing):
           ```yaml
           # SonarQube integration
           - name: SonarQube Scan
             run: |
               ./mvnw sonar:sonar \
                 -Dsonar.projectKey=positivity-pos \
                 -Dsonar.host.url=${{ secrets.SONAR_HOST_URL }} \
                 -Dsonar.login=${{ secrets.SONAR_TOKEN }}

           # SpotBugs for Java security issues
           - name: SpotBugs Security Scan
             run: ./mvnw spotbugs:check

           # OWASP Dependency Check
           - name: OWASP Dependency Check
             run: ./mvnw org.owasp:dependency-check-maven:check
           ```

        2. DAST (Dynamic Application Security Testing):
           ```yaml
           # OWASP ZAP integration
           - name: Start Application
             run: |
               docker-compose up -d
               sleep 30  # Wait for app to start

           - name: OWASP ZAP Scan
             run: |
               docker run -v $(pwd):/zap/wrk/:rw \
                 -t owasp/zap2docker-stable zap-baseline.py \
                 -t http://localhost:8080 \
                 -g gen.conf \
                 -r zap-report.html

           - name: Upload ZAP Report
             uses: actions/upload-artifact@v3
             with:
               name: zap-report
               path: zap-report.html
           ```

        3. Dependency Vulnerability Scanning:
           ```xml
           <!-- Maven OWASP Dependency Check -->
           <plugin>
               <groupId>org.owasp</groupId>
               <artifactId>dependency-check-maven</artifactId>
               <configuration>
                   <failBuildOnCVSS>7</failBuildOnCVSS>
                   <suppressionFiles>
                       <suppressionFile>owasp-suppressions.xml</suppressionFile>
                   </suppressionFiles>
               </configuration>
           </plugin>
           ```

        4. Container Security Scanning:
           ```bash
           # Trivy container scanning
           - name: Container Security Scan
             run: |
               docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
                 -v $HOME/Library/Caches:/root/.cache/ \
                 aquasec/trivy image --exit-code 1 --severity HIGH,CRITICAL pos-service:latest

           # Snyk container scanning
           - name: Snyk Container Scan
             run: |
               snyk container test pos-service:latest --severity-threshold=high
           ```

        5. Security Quality Gates:
           - Fail build on HIGH/CRITICAL vulnerabilities
           - Require security scan approval for production deployments
           - Implement security metrics in dashboards
           - Automated security report generation
        """;
  }

  private String generatePipelineOrchestrationGuidance(AgentConsultationRequest request) {
    String query = request.query().toLowerCase();

    if (query.contains("jenkins")) {
      return generateJenkinsGuidance();
    } else if (query.contains("github actions")) {
      return generateGitHubActionsGuidance();
    } else if (query.contains("gitlab ci")) {
      return generateGitLabCIGuidance();
    } else {
      return generateGeneralOrchestrationGuidance();
    }
  }

  private String generateJenkinsGuidance() {
    return """
        Jenkins Pipeline Configuration:

        1. Declarative Pipeline for Microservices:
           ```groovy
           pipeline {
               agent any

               environment {
                   DOCKER_REGISTRY = 'your-registry.com'
                   KUBECONFIG = credentials('kubeconfig')
               }

               stages {
                   stage('Checkout') {
                       steps {
                           checkout scm
                       }
                   }

                   stage('Build') {
                       parallel {
                           stage('Maven Build') {
                               steps {
                                   sh './mvnw clean package -DskipTests'
                               }
                           }
                           stage('Docker Build') {
                               steps {
                                   sh 'docker build -t ${DOCKER_REGISTRY}/pos-service:${BUILD_NUMBER} .'
                               }
                           }
                       }
                   }

                   stage('Test') {
                       parallel {
                           stage('Unit Tests') {
                               steps {
                                   sh './mvnw test'
                               }
                               post {
                                   always {
                                       publishTestResults testResultsPattern: 'target/surefire-reports/*.xml'
                                   }
                               }
                           }
                           stage('Security Scan') {
                               steps {
                                   sh './mvnw org.owasp:dependency-check-maven:check'
                               }
                           }
                       }
                   }

                   stage('Deploy') {
                       when {
                           branch 'main'
                       }
                       steps {
                           sh 'kubectl apply -f k8s/'
                           sh 'kubectl set image deployment/pos-service pos-service=${DOCKER_REGISTRY}/pos-service:${BUILD_NUMBER}'
                       }
                   }
               }

               post {
                   always {
                       cleanWs()
                   }
                   failure {
                       emailext (
                           subject: "Build Failed: ${env.JOB_NAME} - ${env.BUILD_NUMBER}",
                           body: "Build failed. Check console output at ${env.BUILD_URL}",
                           to: "${env.CHANGE_AUTHOR_EMAIL}"
                       )
                   }
               }
           }
           ```
        """;
  }

  private String generateGitHubActionsGuidance() {
    return """
        GitHub Actions Workflow Configuration:

        1. Complete CI/CD Workflow:
           ```yaml
           name: CI/CD Pipeline

           on:
             push:
               branches: [ main, develop ]
             pull_request:
               branches: [ main ]

           env:
             REGISTRY: ghcr.io
             IMAGE_NAME: ${{ github.repository }}

           jobs:
             test:
               runs-on: ubuntu-latest

               services:
                 postgres:
                   image: postgres:15
                   env:
                     POSTGRES_PASSWORD: postgres
                   options: >-
                     --health-cmd pg_isready
                     --health-interval 10s
                     --health-timeout 5s
                     --health-retries 5

               steps:
               - uses: actions/checkout@v4

               - name: Set up JDK 21
                 uses: actions/setup-java@v3
                 with:
                   java-version: '21'
                   distribution: 'temurin'

               - name: Cache Maven dependencies
                 uses: actions/cache@v3
                 with:
                   path: ~/.m2
                   key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}

               - name: Run tests
                 run: ./mvnw verify

               - name: Upload coverage reports
                 uses: codecov/codecov-action@v3

             build-and-deploy:
               needs: test
               runs-on: ubuntu-latest
               if: github.ref == 'refs/heads/main'

               steps:
               - uses: actions/checkout@v4

               - name: Log in to Container Registry
                 uses: docker/login-action@v2
                 with:
                   registry: ${{ env.REGISTRY }}
                   username: ${{ github.actor }}
                   password: ${{ secrets.GITHUB_TOKEN }}

               - name: Build and push Docker image
                 uses: docker/build-push-action@v4
                 with:
                   context: .
                   push: true
                   tags: ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:latest
           ```
        """;
  }

  private String generateRollingDeploymentGuidance() {
    return """
        Rolling Deployment Strategy:

        1. Kubernetes Rolling Update Configuration:
           ```yaml
           apiVersion: apps/v1
           kind: Deployment
           metadata:
             name: pos-service
           spec:
             replicas: 6
             strategy:
               type: RollingUpdate
               rollingUpdate:
                 maxUnavailable: 1
                 maxSurge: 2
             template:
               spec:
                 containers:
                 - name: pos-service
                   image: pos-service:latest
                   readinessProbe:
                     httpGet:
                       path: /actuator/health/readiness
                       port: 8080
                     initialDelaySeconds: 30
                     periodSeconds: 10
                   livenessProbe:
                     httpGet:
                       path: /actuator/health/liveness
                       port: 8080
                     initialDelaySeconds: 60
                     periodSeconds: 30
           ```

        2. Deployment Commands:
           ```bash
           # Update image and trigger rolling update
           kubectl set image deployment/pos-service pos-service=pos-service:v2.0.0

           # Monitor rollout status
           kubectl rollout status deployment/pos-service --timeout=600s

           # Rollback if needed
           kubectl rollout undo deployment/pos-service
           ```
        """;
  }

  private String generateGeneralBuildGuidance() {
    return """
        General Build Pipeline Best Practices:

        1. Build Optimization:
           - Use build caching (Maven/Gradle daemon, Docker layer caching)
           - Parallel builds for independent modules
           - Incremental builds when possible
           - Artifact caching between pipeline runs

        2. Build Validation:
           - Compile-time checks and static analysis
           - Unit test execution with coverage thresholds
           - Integration test execution with TestContainers
           - Security vulnerability scanning

        3. Artifact Management:
           - Semantic versioning for releases
           - Artifact signing for security
           - Multi-architecture builds (AMD64, ARM64)
           - Artifact retention policies
        """;
  }

  private String generateGeneralDeploymentGuidance() {
    return """
        General Deployment Strategy Guidelines:

        1. Deployment Patterns Comparison:
           - **Blue-Green**: Zero downtime, requires 2x resources, instant rollback
           - **Canary**: Gradual rollout, risk mitigation, requires traffic splitting
           - **Rolling**: Resource efficient, gradual replacement, built-in rollback

        2. Health Checks and Monitoring:
           - Implement readiness and liveness probes
           - Monitor key metrics during deployment
           - Automated rollback on health check failures
           - Post-deployment verification tests

        3. Database Migration Handling:
           - Backward-compatible schema changes
           - Separate migration jobs before deployment
           - Feature flags for new functionality
           - Database rollback strategies
        """;
  }

  private String generateGitLabCIGuidance() {
    return """
        GitLab CI/CD Pipeline Configuration:

        1. Complete .gitlab-ci.yml:
           ```yaml
           stages:
             - build
             - test
             - security
             - deploy

           variables:
             MAVEN_OPTS: "-Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository"
             DOCKER_DRIVER: overlay2

           cache:
             paths:
               - .m2/repository/
               - target/

           build:
             stage: build
             image: openjdk:21-jdk
             script:
               - ./mvnw clean compile
             artifacts:
               paths:
                 - target/
               expire_in: 1 hour

           test:
             stage: test
             image: openjdk:21-jdk
             services:
               - postgres:15
             variables:
               POSTGRES_DB: testdb
               POSTGRES_USER: test
               POSTGRES_PASSWORD: test
             script:
               - ./mvnw test
             coverage: '/Total.*?([0-9]{1,3})%/'
             artifacts:
               reports:
                 junit: target/surefire-reports/TEST-*.xml
                 coverage_report:
                   coverage_format: jacoco
                   path: target/site/jacoco/jacoco.xml

           security:
             stage: security
             image: openjdk:21-jdk
             script:
               - ./mvnw org.owasp:dependency-check-maven:check
             artifacts:
               reports:
                 dependency_scanning: target/dependency-check-report.json

           deploy:
             stage: deploy
             image: alpine/k8s:latest
             script:
               - kubectl apply -f k8s/
             only:
               - main
           ```
        """;
  }

  private String generateGeneralOrchestrationGuidance() {
    return """
        General Pipeline Orchestration Best Practices:

        1. Pipeline Design Principles:
           - Fail fast with early validation stages
           - Parallel execution for independent tasks
           - Proper stage dependencies and artifacts
           - Environment-specific deployment gates

        2. Pipeline Security:
           - Secure credential management
           - Least privilege access principles
           - Audit trails for all pipeline executions
           - Signed artifacts and secure registries

        3. Monitoring and Observability:
           - Pipeline execution metrics and dashboards
           - Build time optimization tracking
           - Failure rate monitoring and alerting
           - Resource utilization monitoring
        """;
  }

  private String generateGeneralCICDGuidance(AgentConsultationRequest request) {
    return """
        Comprehensive CI/CD Pipeline Strategy:

        1. Pipeline Stages Overview:
           - **Source**: Code checkout and validation
           - **Build**: Compilation, packaging, and artifact creation
           - **Test**: Unit, integration, contract, and security testing
           - **Deploy**: Environment-specific deployments with strategies
           - **Monitor**: Post-deployment validation and monitoring

        2. Quality Gates:
           - Code coverage thresholds (minimum 80%)
           - Security vulnerability scanning (no HIGH/CRITICAL)
           - Performance regression testing
           - Manual approval for production deployments

        3. Environment Promotion:
           - Development → Testing → Staging → Production
           - Automated promotion with quality gates
           - Environment-specific configuration management
           - Rollback capabilities at each stage

        4. Best Practices:
           - Infrastructure as Code for pipeline definitions
           - Immutable artifacts across environments
           - Comprehensive logging and monitoring
           - Regular pipeline maintenance and optimization
        """;
  }

  private List<String> generateCICDRecommendations(AgentConsultationRequest request) {
    String query = request.query().toLowerCase();

    if (query.contains("build") || query.contains("maven") || query.contains("gradle")) {
      return List.of(
          "Implement build caching for faster pipeline execution",
          "Use multi-stage Docker builds for optimized images",
          "Configure parallel builds for independent modules",
          "Implement artifact signing for security compliance");
    } else if (query.contains("test") || query.contains("testing")) {
      return List.of(
          "Implement comprehensive testing pyramid (unit, integration, contract)",
          "Use TestContainers for integration testing with real databases",
          "Configure code coverage thresholds and quality gates",
          "Implement automated security testing in pipeline");
    } else if (query.contains("deployment") || query.contains("deploy")) {
      return List.of(
          "Choose deployment strategy based on risk tolerance and resources",
          "Implement proper health checks and monitoring",
          "Configure automated rollback on deployment failures",
          "Use feature flags for safer production deployments");
    } else if (query.contains("security")) {
      return List.of(
          "Integrate SAST and DAST scanning in pipeline",
          "Implement dependency vulnerability scanning",
          "Configure container security scanning",
          "Set up security quality gates with failure thresholds");
    } else {
      return List.of(
          "Design pipeline with fail-fast principles and parallel execution",
          "Implement comprehensive quality gates at each stage",
          "Use Infrastructure as Code for pipeline definitions",
          "Configure proper monitoring and alerting for pipeline health",
          "Implement automated rollback capabilities for all deployment strategies");
    }
  }
}