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
 * Documentation Agent - Provides technical documentation and API documentation
 * standards
 * 
 * Implements REQ-009 capabilities:
 * - Technical documentation, README, and architectural documentation
 * - Comprehensive API documentation and synchronization
 * - Documentation completeness and accuracy validation
 * - Consistent formatting and documentation generation
 * - Documentation maintenance and version control integration
 */
@Component
public class DocumentationAgent extends BaseAgent {

    public DocumentationAgent() {
        super(
                "documentation-agent",
                "Documentation Agent",
                "documentation",
                Set.of("technical-docs", "readme", "architectural-docs", "api-docs", "openapi",
                        "swagger", "documentation", "formatting", "generation", "synchronization",
                        "completeness", "accuracy", "validation", "maintenance", "version-control",
                        "markdown", "asciidoc", "javadoc", "spring-rest-docs", "confluence",
                        "wiki", "changelog", "release-notes", "user-guides", "developer-guides"),
                Set.of(), // No dependencies
                new AgentPerformanceSpec(Duration.ofSeconds(3), 0.94, 0.999, 10, Duration.ofMinutes(5)));
    }

    @Override
    protected AgentGuidanceResponse processGuidanceRequest(AgentConsultationRequest request) {
        return AgentGuidanceResponse.success(
                request.requestId(),
                getId(),
                generateDocumentationGuidance(request),
                0.94,
                List.of("Technical Documentation", "API Documentation", "Documentation Standards"),
                Duration.ZERO);
    }

    private String generateDocumentationGuidance(AgentConsultationRequest request) {
        String baseGuidance = "POS System Documentation Analysis for " + request.domain() + ":\n\n";
        String query = request.query().toLowerCase();

        // Technical documentation, README, and architectural documentation (REQ-009.1)
        if (query.contains("technical") || query.contains("readme") || query.contains("architectural") ||
                query.contains("architecture") || query.contains("technical-docs")) {
            return baseGuidance + generateTechnicalDocumentationGuidance(request);
        }
        // Comprehensive API documentation and synchronization (REQ-009.2)
        if (query.contains("api") || query.contains("openapi") || query.contains("swagger") ||
                query.contains("rest-docs") || query.contains("synchronization") || query.contains("api-docs") ||
                query.contains("rest api") || query.contains("endpoint")) {
            return baseGuidance + generateApiDocumentationGuidance(request);
        }

        // Documentation completeness and accuracy validation (REQ-009.3)
        if (query.contains("completeness") || query.contains("accuracy") || query.contains("validation") ||
                query.contains("review") || query.contains("quality") || query.contains("audit")) {
            return baseGuidance + generateDocumentationValidationGuidance(request);
        }

        // Consistent formatting and documentation generation (REQ-009.4)
        if (query.contains("formatting") || query.contains("generation") || query.contains("templates") ||
                query.contains("standards") || query.contains("style") || query.contains("consistency")) {
            return baseGuidance + generateFormattingStandardsGuidance(request);
        }

        // Documentation maintenance and version control integration (REQ-009.5)
        if (query.contains("maintenance") || query.contains("version-control") || query.contains("git") ||
                query.contains("updates") || query.contains("lifecycle") || query.contains("versioning")) {
            return baseGuidance + generateMaintenanceGuidance(request);
        }

        // Specific documentation types
        if (query.contains("javadoc") || query.contains("code-docs") || query.contains("inline")) {
            return baseGuidance + generateCodeDocumentationGuidance(request);
        }

        if (query.contains("user-guide") || query.contains("user-docs") || query.contains("end-user")) {
            return baseGuidance + generateUserDocumentationGuidance(request);
        }

        if (query.contains("developer-guide") || query.contains("dev-docs") || query.contains("onboarding")) {
            return baseGuidance + generateDeveloperDocumentationGuidance(request);
        }

        // Catch-all for documentation queries
        if (query.contains("documentation") || query.contains("docs")) {
            return baseGuidance + generateGeneralDocumentationGuidance();
        }

        // General documentation guidance
        return baseGuidance + generateGeneralDocumentationGuidance();
    }

    private String generateTechnicalDocumentationGuidance(AgentConsultationRequest request) {
        return "Technical Documentation, README & Architectural Documentation:\n\n" +
                "README.md STRUCTURE:\n" +
                "```markdown\n" +
                "# Service Name (pos-order)\n" +
                "\n" +
                "## Overview\n" +
                "Brief description of the service's purpose and business domain.\n" +
                "\n" +
                "## Architecture\n" +
                "- **Domain**: Order Management\n" +
                "- **Database**: PostgreSQL\n" +
                "- **Dependencies**: pos-customer, pos-inventory, pos-payment\n" +
                "- **Integration**: API Gateway, Event Bus (Kafka)\n" +
                "\n" +
                "## Quick Start\n" +
                "```bash\n" +
                "# Build and run locally\n" +
                "./mvnw spring-boot:run\n" +
                "\n" +
                "# Run with Docker\n" +
                "docker-compose up pos-order\n" +
                "```\n" +
                "\n" +
                "## API Documentation\n" +
                "- [OpenAPI Spec](./docs/openapi.yaml)\n" +
                "- [Postman Collection](./docs/postman-collection.json)\n" +
                "- [Interactive Docs](http://localhost:8080/swagger-ui.html)\n" +
                "\n" +
                "## Configuration\n" +
                "| Property | Description | Default | Required |\n" +
                "|----------|-------------|---------|----------|\n" +
                "| `spring.datasource.url` | Database URL | - | Yes |\n" +
                "| `pos.order.timeout` | Order timeout | 30s | No |\n" +
                "\n" +
                "## Monitoring\n" +
                "- [Health Check](http://localhost:8080/actuator/health)\n" +
                "- [Metrics](http://localhost:8080/actuator/prometheus)\n" +
                "- [Grafana Dashboard](http://grafana:3000/d/pos-order)\n" +
                "```\n\n" +

                "ARCHITECTURAL DOCUMENTATION:\n" +
                "```markdown\n" +
                "# Architecture Decision Record (ADR)\n" +
                "\n" +
                "## ADR-001: Database Per Service Pattern\n" +
                "\n" +
                "### Status\n" +
                "Accepted\n" +
                "\n" +
                "### Context\n" +
                "Each microservice needs independent data storage to ensure loose coupling.\n" +
                "\n" +
                "### Decision\n" +
                "Implement database-per-service pattern with PostgreSQL instances.\n" +
                "\n" +
                "### Consequences\n" +
                "- **Positive**: Service independence, technology diversity\n" +
                "- **Negative**: Data consistency complexity, operational overhead\n" +
                "```\n\n" +

                "TECHNICAL SPECIFICATIONS:\n" +
                "- **Service Boundaries**: Clear domain separation with well-defined interfaces\n" +
                "- **Data Models**: Entity relationship diagrams with business context\n" +
                "- **Integration Patterns**: Event-driven communication, API contracts\n" +
                "- **Security Model**: Authentication, authorization, and data protection\n" +
                "- **Deployment Architecture**: Container orchestration and scaling strategies\n";
    }

    private String generateApiDocumentationGuidance(AgentConsultationRequest request) {
        return "Comprehensive API Documentation & Synchronization:\n\n" +
                "OPENAPI 3.0 SPECIFICATION:\n" +
                "```yaml\n" +
                "openapi: 3.0.3\n" +
                "info:\n" +
                "  title: POS Order Service API\n" +
                "  version: 1.0.0\n" +
                "  description: Order management and processing\n" +
                "  contact:\n" +
                "    name: Order Team\n" +
                "    email: order-team@positivity.com\n" +
                "servers:\n" +
                "  - url: https://api.positivity.com/v1\n" +
                "    description: Production\n" +
                "  - url: http://localhost:8080\n" +
                "    description: Development\n" +
                "\n" +
                "paths:\n" +
                "  /orders:\n" +
                "    post:\n" +
                "      summary: Create new order\n" +
                "      operationId: createOrder\n" +
                "      tags: [Orders]\n" +
                "      requestBody:\n" +
                "        required: true\n" +
                "        content:\n" +
                "          application/json:\n" +
                "            schema:\n" +
                "              $ref: '#/components/schemas/OrderRequest'\n" +
                "            examples:\n" +
                "              simple_order:\n" +
                "                summary: Simple order example\n" +
                "                value:\n" +
                "                  customerId: \"12345\"\n" +
                "                  items: [{\"productId\": \"P001\", \"quantity\": 2}]\n" +
                "      responses:\n" +
                "        '201':\n" +
                "          description: Order created successfully\n" +
                "          content:\n" +
                "            application/json:\n" +
                "              schema:\n" +
                "                $ref: '#/components/schemas/Order'\n" +
                "        '400':\n" +
                "          $ref: '#/components/responses/BadRequest'\n" +
                "        '422':\n" +
                "          $ref: '#/components/responses/ValidationError'\n" +
                "```\n\n" +

                "SPRING REST DOCS INTEGRATION:\n" +
                "```java\n" +
                "@Test\n" +
                "public void createOrderDocumentation() throws Exception {\n" +
                "    mockMvc.perform(post(\"/orders\")\n" +
                "            .contentType(MediaType.APPLICATION_JSON)\n" +
                "            .content(objectMapper.writeValueAsString(orderRequest)))\n" +
                "        .andExpect(status().isCreated())\n" +
                "        .andDo(document(\"create-order\",\n" +
                "            requestFields(\n" +
                "                fieldWithPath(\"customerId\").description(\"Customer identifier\"),\n" +
                "                fieldWithPath(\"items[].productId\").description(\"Product identifier\"),\n" +
                "                fieldWithPath(\"items[].quantity\").description(\"Item quantity\")\n" +
                "            ),\n" +
                "            responseFields(\n" +
                "                fieldWithPath(\"id\").description(\"Order identifier\"),\n" +
                "                fieldWithPath(\"status\").description(\"Order status\"),\n" +
                "                fieldWithPath(\"total\").description(\"Order total amount\")\n" +
                "            )\n" +
                "        ));\n" +
                "}\n" +
                "```\n\n" +

                "DOCUMENTATION SYNCHRONIZATION:\n" +
                "```bash\n" +
                "# Gradle task for API docs generation\n" +
                "./gradlew asciidoctor\n" +
                "\n" +
                "# Maven plugin for OpenAPI generation\n" +
                "mvn springdoc-openapi:generate\n" +
                "\n" +
                "# CI/CD integration\n" +
                "- name: Generate API Documentation\n" +
                "  run: |\n" +
                "    ./mvnw test\n" +
                "    ./mvnw asciidoctor:process-asciidoc\n" +
                "    cp target/generated-docs/* docs/api/\n" +
                "```\n\n" +

                "POS-SPECIFIC API DOCUMENTATION:\n" +
                "- **Order Flow**: Customer selection → Item addition → Payment → Fulfillment\n" +
                "- **Error Handling**: Standardized error codes and messages across all services\n" +
                "- **Rate Limiting**: API throttling and usage guidelines\n" +
                "- **Authentication**: JWT token requirements and refresh patterns\n" +
                "- **Versioning**: Backward compatibility and deprecation policies\n";
    }

    private String generateDocumentationValidationGuidance(AgentConsultationRequest request) {
        return "Documentation Completeness & Accuracy Validation:\n\n" +
                "DOCUMENTATION AUDIT CHECKLIST:\n" +
                "```markdown\n" +
                "## Service Documentation Audit\n" +
                "\n" +
                "### Required Documentation ✓/✗\n" +
                "- [ ] README.md with service overview and quick start\n" +
                "- [ ] OpenAPI specification with all endpoints documented\n" +
                "- [ ] Architecture Decision Records (ADRs) for major decisions\n" +
                "- [ ] Configuration documentation with all properties\n" +
                "- [ ] Deployment guide with Docker and Kubernetes instructions\n" +
                "- [ ] Monitoring and observability documentation\n" +
                "- [ ] Error handling and troubleshooting guide\n" +
                "- [ ] Security and authentication documentation\n" +
                "\n" +
                "### API Documentation Quality\n" +
                "- [ ] All endpoints have descriptions and examples\n" +
                "- [ ] Request/response schemas are complete\n" +
                "- [ ] Error responses are documented with codes\n" +
                "- [ ] Authentication requirements are clear\n" +
                "- [ ] Rate limiting and usage guidelines provided\n" +
                "\n" +
                "### Code Documentation\n" +
                "- [ ] Public classes have Javadoc comments\n" +
                "- [ ] Complex business logic is explained\n" +
                "- [ ] Configuration properties are documented\n" +
                "- [ ] Integration points are clearly described\n" +
                "```\n\n" +

                "AUTOMATED VALIDATION TOOLS:\n" +
                "```java\n" +
                "@Component\n" +
                "public class DocumentationValidator {\n" +
                "    \n" +
                "    public ValidationResult validateApiDocumentation(OpenApiSpec spec) {\n" +
                "        List<ValidationIssue> issues = new ArrayList<>();\n" +
                "        \n" +
                "        // Check all endpoints have descriptions\n" +
                "        spec.getPaths().forEach((path, pathItem) -> {\n" +
                "            pathItem.getOperations().forEach((method, operation) -> {\n" +
                "                if (operation.getDescription() == null || operation.getDescription().isEmpty()) {\n" +
                "                    issues.add(new ValidationIssue(\n" +
                "                        \"Missing description for \" + method + \" \" + path,\n" +
                "                        ValidationLevel.ERROR\n" +
                "                    ));\n" +
                "                }\n" +
                "                \n" +
                "                // Validate examples exist\n" +
                "                if (operation.getRequestBody() != null) {\n" +
                "                    validateExamples(operation.getRequestBody(), issues);\n" +
                "                }\n" +
                "            });\n" +
                "        });\n" +
                "        \n" +
                "        return new ValidationResult(issues);\n" +
                "    }\n" +
                "    \n" +
                "    public ValidationResult validateReadmeCompleteness(String readmeContent) {\n" +
                "        List<String> requiredSections = Arrays.asList(\n" +
                "            \"# \", \"## Overview\", \"## Quick Start\", \"## API Documentation\",\n" +
                "            \"## Configuration\", \"## Monitoring\"\n" +
                "        );\n" +
                "        \n" +
                "        return requiredSections.stream()\n" +
                "            .filter(section -> !readmeContent.contains(section))\n" +
                "            .map(section -> new ValidationIssue(\n" +
                "                \"Missing required section: \" + section,\n" +
                "                ValidationLevel.WARNING\n" +
                "            ))\n" +
                "            .collect(Collectors.toList());\n" +
                "    }\n" +
                "}\n" +
                "```\n\n" +

                "DOCUMENTATION QUALITY METRICS:\n" +
                "```yaml\n" +
                "# SonarQube quality gate for documentation\n" +
                "sonar.coverage.exclusions: \"**/docs/**\"\n" +
                "sonar.cpd.exclusions: \"**/docs/**\"\n" +
                "\n" +
                "# Custom metrics\n" +
                "documentation.coverage:\n" +
                "  api_endpoints_documented: 95%\n" +
                "  public_methods_documented: 80%\n" +
                "  configuration_properties_documented: 100%\n" +
                "  \n" +
                "documentation.freshness:\n" +
                "  max_age_days: 30\n" +
                "  auto_update_on_code_change: true\n" +
                "```\n\n" +

                "POS DOCUMENTATION STANDARDS:\n" +
                "- **Business Context**: Every service documents its business purpose\n" +
                "- **Integration Points**: Clear documentation of service dependencies\n" +
                "- **Data Models**: Entity relationships and business rules explained\n" +
                "- **Error Scenarios**: Common failure modes and recovery procedures\n" +
                "- **Performance Characteristics**: Expected load and response times\n";
    }

    private String generateFormattingStandardsGuidance(AgentConsultationRequest request) {
        return "Consistent Formatting & Documentation Generation:\n\n" +
                "MARKDOWN FORMATTING STANDARDS:\n" +
                "```markdown\n" +
                "# Document Title (H1 - Only One Per Document)\n" +
                "\n" +
                "## Section Headers (H2 - Main Sections)\n" +
                "\n" +
                "### Subsections (H3 - Detailed Topics)\n" +
                "\n" +
                "#### Implementation Details (H4 - Specific Items)\n" +
                "\n" +
                "**Bold Text**: For emphasis and important concepts\n" +
                "*Italic Text*: For technical terms and variables\n" +
                "`Inline Code`: For code snippets, file names, and commands\n" +
                "\n" +
                "```java\n" +
                "// Code blocks with language specification\n" +
                "public class Example {\n" +
                "    // Always include comments for clarity\n" +
                "}\n" +
                "```\n" +
                "\n" +
                "| Column 1 | Column 2 | Column 3 |\n" +
                "|----------|----------|----------|\n" +
                "| Data     | Data     | Data     |\n" +
                "\n" +
                "- Bullet points for lists\n" +
                "- Use consistent indentation\n" +
                "  - Nested items with 2-space indentation\n" +
                "\n" +
                "1. Numbered lists for procedures\n" +
                "2. Sequential steps\n" +
                "   a. Sub-steps with letter notation\n" +
                "\n" +
                "> **Note**: Use blockquotes for important notes\n" +
                "> and warnings that need attention.\n" +
                "```\n\n" +

                "DOCUMENTATION TEMPLATES:\n" +
                "```yaml\n" +
                "# Service README Template\n" +
                "template: |\n" +
                "  # {{service.name}}\n" +
                "  \n" +
                "  ## Overview\n" +
                "  {{service.description}}\n" +
                "  \n" +
                "  **Domain**: {{service.domain}}\n" +
                "  **Database**: {{service.database}}\n" +
                "  **Port**: {{service.port}}\n" +
                "  \n" +
                "  ## Quick Start\n" +
                "  ```bash\n" +
                "  # Local development\n" +
                "  ./mvnw spring-boot:run\n" +
                "  \n" +
                "  # Docker\n" +
                "  docker-compose up {{service.name}}\n" +
                "  ```\n" +
                "  \n" +
                "  ## API Documentation\n" +
                "  - [OpenAPI Spec](./docs/openapi.yaml)\n" +
                "  - [Interactive Docs](http://localhost:{{service.port}}/swagger-ui.html)\n" +
                "  \n" +
                "  ## Configuration\n" +
                "  {{#each service.configuration}}\n" +
                "  - `{{key}}`: {{description}} ({{#if required}}Required{{else}}Optional{{/if}})\n" +
                "  {{/each}}\n" +
                "\n" +
                "# API Documentation Template\n" +
                "api_template: |\n" +
                "  ## {{endpoint.method}} {{endpoint.path}}\n" +
                "  \n" +
                "  {{endpoint.description}}\n" +
                "  \n" +
                "  **Parameters**:\n" +
                "  {{#each endpoint.parameters}}\n" +
                "  - `{{name}}` ({{type}}): {{description}}\n" +
                "  {{/each}}\n" +
                "  \n" +
                "  **Example Request**:\n" +
                "  ```json\n" +
                "  {{endpoint.example.request}}\n" +
                "  ```\n" +
                "  \n" +
                "  **Example Response**:\n" +
                "  ```json\n" +
                "  {{endpoint.example.response}}\n" +
                "  ```\n" +
                "```\n\n" +

                "AUTOMATED GENERATION TOOLS:\n" +
                "```java\n" +
                "@Component\n" +
                "public class DocumentationGenerator {\n" +
                "    \n" +
                "    @Autowired\n" +
                "    private TemplateEngine templateEngine;\n" +
                "    \n" +
                "    public void generateServiceDocumentation(ServiceMetadata service) {\n" +
                "        // Generate README from template\n" +
                "        String readme = templateEngine.process(\"service-readme\", \n" +
                "            Map.of(\"service\", service));\n" +
                "        writeToFile(\"README.md\", readme);\n" +
                "        \n" +
                "        // Generate API documentation\n" +
                "        String apiDocs = generateApiDocumentation(service.getEndpoints());\n" +
                "        writeToFile(\"docs/api.md\", apiDocs);\n" +
                "        \n" +
                "        // Generate configuration documentation\n" +
                "        String configDocs = generateConfigurationDocs(service.getProperties());\n" +
                "        writeToFile(\"docs/configuration.md\", configDocs);\n" +
                "    }\n" +
                "    \n" +
                "    private String generateApiDocumentation(List<Endpoint> endpoints) {\n" +
                "        return endpoints.stream()\n" +
                "            .map(endpoint -> templateEngine.process(\"api-endpoint\", \n" +
                "                Map.of(\"endpoint\", endpoint)))\n" +
                "            .collect(Collectors.joining(\"\\n\\n\"));\n" +
                "    }\n" +
                "}\n" +
                "```\n\n" +

                "STYLE GUIDE ENFORCEMENT:\n" +
                "- **Line Length**: Maximum 120 characters for code, 80 for documentation\n" +
                "- **Indentation**: 2 spaces for nested lists, 4 spaces for code blocks\n" +
                "- **Links**: Use descriptive text, avoid \"click here\" or bare URLs\n" +
                "- **Images**: Include alt text and appropriate sizing\n" +
                "- **Code Examples**: Always include context and expected output\n";
    }

    private String generateMaintenanceGuidance(AgentConsultationRequest request) {
        return "Documentation Maintenance & Version Control Integration:\n\n" +
                "GIT INTEGRATION WORKFLOW:\n" +
                "```bash\n" +
                "# Documentation branch strategy\n" +
                "git checkout -b docs/update-api-documentation\n" +
                "\n" +
                "# Automated documentation updates\n" +
                ".github/workflows/docs-update.yml:\n" +
                "name: Update Documentation\n" +
                "on:\n" +
                "  push:\n" +
                "    paths:\n" +
                "      - 'src/main/java/**/*.java'\n" +
                "      - 'src/main/resources/application.yml'\n" +
                "jobs:\n" +
                "  update-docs:\n" +
                "    runs-on: ubuntu-latest\n" +
                "    steps:\n" +
                "      - uses: actions/checkout@v3\n" +
                "      - name: Generate API Documentation\n" +
                "        run: |\n" +
                "          ./mvnw springdoc-openapi:generate\n" +
                "          ./mvnw asciidoctor:process-asciidoc\n" +
                "      - name: Commit Documentation Updates\n" +
                "        run: |\n" +
                "          git config --local user.email \"action@github.com\"\n" +
                "          git config --local user.name \"GitHub Action\"\n" +
                "          git add docs/\n" +
                "          git diff --staged --quiet || git commit -m \"Auto-update documentation\"\n" +
                "          git push\n" +
                "```\n\n" +

                "DOCUMENTATION LIFECYCLE MANAGEMENT:\n" +
                "```java\n" +
                "@Component\n" +
                "public class DocumentationLifecycleManager {\n" +
                "    \n" +
                "    @EventListener\n" +
                "    public void onCodeChange(CodeChangeEvent event) {\n" +
                "        if (event.affectsPublicApi()) {\n" +
                "            scheduleDocumentationUpdate(event.getChangedFiles());\n" +
                "        }\n" +
                "    }\n" +
                "    \n" +
                "    @Scheduled(cron = \"0 0 2 * * MON\") // Weekly review\n" +
                "    public void reviewDocumentationFreshness() {\n" +
                "        List<DocumentationFile> staleFiles = findStaleDocumentation();\n" +
                "        staleFiles.forEach(file -> {\n" +
                "            createDocumentationUpdateTask(file);\n" +
                "            notifyDocumentationOwner(file);\n" +
                "        });\n" +
                "    }\n" +
                "    \n" +
                "    private List<DocumentationFile> findStaleDocumentation() {\n" +
                "        return documentationRepository.findAll().stream()\n" +
                "            .filter(doc -> doc.getLastModified()\n" +
                "                .isBefore(LocalDateTime.now().minusDays(30)))\n" +
                "            .collect(Collectors.toList());\n" +
                "    }\n" +
                "}\n" +
                "```\n\n" +

                "VERSION CONTROL BEST PRACTICES:\n" +
                "```markdown\n" +
                "## Documentation Versioning Strategy\n" +
                "\n" +
                "### Branch Structure\n" +
                "- `main`: Current production documentation\n" +
                "- `develop`: Documentation for upcoming features\n" +
                "- `docs/*`: Feature-specific documentation updates\n" +
                "\n" +
                "### Commit Message Format\n" +
                "```\n" +
                "docs: <type>(<scope>): <description>\n" +
                "\n" +
                "Types:\n" +
                "- add: New documentation\n" +
                "- update: Existing documentation changes\n" +
                "- fix: Corrections and clarifications\n" +
                "- remove: Deprecated documentation removal\n" +
                "\n" +
                "Examples:\n" +
                "docs: add(api): OpenAPI specification for order endpoints\n" +
                "docs: update(readme): Configuration section with new properties\n" +
                "docs: fix(troubleshooting): Correct database connection steps\n" +
                "```\n" +
                "\n" +
                "### Review Process\n" +
                "1. **Technical Review**: Accuracy and completeness\n" +
                "2. **Editorial Review**: Grammar, style, and clarity\n" +
                "3. **Stakeholder Review**: Business alignment and user needs\n" +
                "4. **Final Approval**: Documentation owner sign-off\n" +
                "```\n\n" +

                "DOCUMENTATION METRICS & MONITORING:\n" +
                "```yaml\n" +
                "# Documentation health metrics\n" +
                "documentation:\n" +
                "  metrics:\n" +
                "    coverage:\n" +
                "      api_endpoints: 95%\n" +
                "      configuration_properties: 100%\n" +
                "      public_methods: 80%\n" +
                "    freshness:\n" +
                "      max_age_days: 30\n" +
                "      review_frequency: weekly\n" +
                "    quality:\n" +
                "      broken_links: 0\n" +
                "      spelling_errors: 0\n" +
                "      formatting_issues: 0\n" +
                "  \n" +
                "  alerts:\n" +
                "    stale_documentation:\n" +
                "      threshold: 45_days\n" +
                "      notification: slack_channel\n" +
                "    missing_api_docs:\n" +
                "      threshold: 1_new_endpoint\n" +
                "      notification: email\n" +
                "```\n\n" +

                "POS DOCUMENTATION MAINTENANCE:\n" +
                "- **Release Notes**: Automated generation from commit messages and PR descriptions\n" +
                "- **API Changelog**: Track breaking changes and deprecations\n" +
                "- **Configuration Updates**: Sync with application.yml changes\n" +
                "- **Integration Documentation**: Keep service dependencies current\n" +
                "- **Troubleshooting Guides**: Update based on support tickets and incidents\n";
    }

    private String generateCodeDocumentationGuidance(AgentConsultationRequest request) {
        return "Code Documentation & Javadoc Standards:\n\n" +
                "JAVADOC BEST PRACTICES:\n" +
                "```java\n" +
                "/**\n" +
                " * Processes customer orders in the POS system.\n" +
                " * \n" +
                " * This service handles the complete order lifecycle from creation\n" +
                " * to fulfillment, including inventory validation, payment processing,\n" +
                " * and order status tracking.\n" +
                " * \n" +
                " * @author Order Management Team\n" +
                " * @version 1.0\n" +
                " * @since 2024-01-01\n" +
                " */\n" +
                "@Service\n" +
                "public class OrderService {\n" +
                "    \n" +
                "    /**\n" +
                "     * Creates a new order for the specified customer.\n" +
                "     * \n" +
                "     * Validates inventory availability, calculates pricing,\n" +
                "     * and initiates the payment process. The order is created\n" +
                "     * in PENDING status until payment confirmation.\n" +
                "     * \n" +
                "     * @param customerId the unique identifier of the customer\n" +
                "     * @param orderItems list of items to include in the order\n" +
                "     * @param paymentMethod the payment method to use\n" +
                "     * @return the created order with assigned ID and status\n" +
                "     * @throws CustomerNotFoundException if customer ID is invalid\n" +
                "     * @throws InsufficientInventoryException if items are out of stock\n" +
                "     * @throws PaymentProcessingException if payment fails\n" +
                "     * \n" +
                "     * @see OrderItem\n" +
                "     * @see PaymentMethod\n" +
                "     */\n" +
                "    public Order createOrder(String customerId, \n" +
                "                           List<OrderItem> orderItems, \n" +
                "                           PaymentMethod paymentMethod) {\n" +
                "        // Implementation\n" +
                "    }\n" +
                "}\n" +
                "```\n\n" +

                "CONFIGURATION DOCUMENTATION:\n" +
                "```java\n" +
                "/**\n" +
                " * Configuration properties for the POS Order Service.\n" +
                " * \n" +
                " * These properties control order processing behavior,\n" +
                " * timeouts, and integration settings.\n" +
                " */\n" +
                "@ConfigurationProperties(prefix = \"pos.order\")\n" +
                "@Data\n" +
                "public class OrderProperties {\n" +
                "    \n" +
                "    /**\n" +
                "     * Maximum time to wait for payment confirmation.\n" +
                "     * \n" +
                "     * After this timeout, the order will be automatically\n" +
                "     * cancelled and inventory will be released.\n" +
                "     * \n" +
                "     * Default: 30 minutes\n" +
                "     */\n" +
                "    private Duration paymentTimeout = Duration.ofMinutes(30);\n" +
                "    \n" +
                "    /**\n" +
                "     * Maximum number of items allowed in a single order.\n" +
                "     * \n" +
                "     * This limit prevents performance issues with large\n" +
                "     * orders and helps with inventory management.\n" +
                "     * \n" +
                "     * Default: 100 items\n" +
                "     */\n" +
                "    private int maxItemsPerOrder = 100;\n" +
                "}\n" +
                "```\n\n" +

                "INLINE DOCUMENTATION STANDARDS:\n" +
                "- **Complex Business Logic**: Explain the 'why' not just the 'what'\n" +
                "- **Algorithm Explanations**: Document time/space complexity\n" +
                "- **Integration Points**: Explain external service dependencies\n" +
                "- **Error Handling**: Document expected exceptions and recovery\n" +
                "- **Performance Considerations**: Note any optimization decisions\n";
    }

    private String generateUserDocumentationGuidance(AgentConsultationRequest request) {
        return "User Documentation & End-User Guides:\n\n" +
                "USER GUIDE STRUCTURE:\n" +
                "```markdown\n" +
                "# POS System User Guide\n" +
                "\n" +
                "## Getting Started\n" +
                "### System Requirements\n" +
                "- Modern web browser (Chrome, Firefox, Safari, Edge)\n" +
                "- Stable internet connection\n" +
                "- Screen resolution: 1024x768 minimum\n" +
                "\n" +
                "### Logging In\n" +
                "1. Navigate to the POS system URL\n" +
                "2. Enter your username and password\n" +
                "3. Click \"Sign In\"\n" +
                "4. Select your store location if prompted\n" +
                "\n" +
                "## Daily Operations\n" +
                "### Processing a Sale\n" +
                "1. **Start New Transaction**\n" +
                "   - Click \"New Sale\" button\n" +
                "   - Scan or enter product codes\n" +
                "   - Verify quantities and prices\n" +
                "\n" +
                "2. **Customer Information**\n" +
                "   - Search for existing customer\n" +
                "   - Or create new customer profile\n" +
                "   - Apply any discounts or promotions\n" +
                "\n" +
                "3. **Payment Processing**\n" +
                "   - Select payment method\n" +
                "   - Process card payment or handle cash\n" +
                "   - Print receipt for customer\n" +
                "\n" +
                "### Handling Returns\n" +
                "1. Locate original transaction\n" +
                "2. Select items to return\n" +
                "3. Choose return reason\n" +
                "4. Process refund to original payment method\n" +
                "```\n\n" +

                "TROUBLESHOOTING GUIDE:\n" +
                "```markdown\n" +
                "## Common Issues and Solutions\n" +
                "\n" +
                "### Payment Processing Issues\n" +
                "**Problem**: Credit card reader not responding\n" +
                "**Solution**: \n" +
                "1. Check cable connections\n" +
                "2. Restart the card reader\n" +
                "3. Try manual card entry\n" +
                "4. Contact IT support if issue persists\n" +
                "\n" +
                "**Problem**: Transaction declined\n" +
                "**Solution**:\n" +
                "1. Ask customer to try different card\n" +
                "2. Verify card is not expired\n" +
                "3. Try smaller amount if possible\n" +
                "4. Suggest alternative payment method\n" +
                "\n" +
                "### Inventory Issues\n" +
                "**Problem**: Product not found in system\n" +
                "**Solution**:\n" +
                "1. Check product code spelling\n" +
                "2. Try alternative product codes\n" +
                "3. Use manual price entry if authorized\n" +
                "4. Report missing product to manager\n" +
                "```\n\n" +

                "TRAINING MATERIALS:\n" +
                "- **Quick Reference Cards**: Laminated cards with common procedures\n" +
                "- **Video Tutorials**: Screen recordings of key workflows\n" +
                "- **Interactive Demos**: Sandbox environment for practice\n" +
                "- **Certification Tests**: Knowledge validation for new users\n";
    }

    private String generateDeveloperDocumentationGuidance(AgentConsultationRequest request) {
        return "Developer Documentation & Onboarding Guides:\n\n" +
                "DEVELOPER ONBOARDING:\n" +
                "```markdown\n" +
                "# Developer Onboarding Guide\n" +
                "\n" +
                "## Prerequisites\n" +
                "- Java 21 (managed via SDKMAN)\n" +
                "- Docker and Docker Compose\n" +
                "- Git and IDE (IntelliJ IDEA recommended)\n" +
                "- Access to company VPN and repositories\n" +
                "\n" +
                "## Environment Setup\n" +
                "1. **Clone Repositories**\n" +
                "   ```bash\n" +
                "   git clone https://github.com/positivity/pos-order.git\n" +
                "   cd pos-order\n" +
                "   ```\n" +
                "\n" +
                "2. **Install Dependencies**\n" +
                "   ```bash\n" +
                "   # Install SDKMAN and Java 21\n" +
                "   curl -s \"https://get.sdkman.io\" | bash\n" +
                "   sdk install java 21.0.5-tem\n" +
                "   \n" +
                "   # Build project\n" +
                "   ./mvnw clean install\n" +
                "   ```\n" +
                "\n" +
                "3. **Start Development Environment**\n" +
                "   ```bash\n" +
                "   docker-compose up -d postgres redis\n" +
                "   ./mvnw spring-boot:run\n" +
                "   ```\n" +
                "\n" +
                "## Development Workflow\n" +
                "1. Create feature branch from `develop`\n" +
                "2. Implement changes with tests\n" +
                "3. Run full test suite\n" +
                "4. Create pull request with documentation updates\n" +
                "5. Address code review feedback\n" +
                "6. Merge after approval\n" +
                "```\n\n" +

                "ARCHITECTURE DOCUMENTATION:\n" +
                "```markdown\n" +
                "# System Architecture\n" +
                "\n" +
                "## Service Overview\n" +
                "The POS system follows a microservices architecture with:\n" +
                "- **API Gateway**: Request routing and authentication\n" +
                "- **Service Discovery**: Eureka for service registration\n" +
                "- **Configuration**: Centralized configuration management\n" +
                "- **Messaging**: Kafka for event-driven communication\n" +
                "\n" +
                "## Data Flow\n" +
                "```mermaid\n" +
                "graph TD\n" +
                "    A[Client] --> B[API Gateway]\n" +
                "    B --> C[Order Service]\n" +
                "    C --> D[Inventory Service]\n" +
                "    C --> E[Payment Service]\n" +
                "    C --> F[Customer Service]\n" +
                "```\n" +
                "\n" +
                "## Design Patterns\n" +
                "- **CQRS**: Command Query Responsibility Segregation\n" +
                "- **Event Sourcing**: For audit trails and state reconstruction\n" +
                "- **Saga Pattern**: For distributed transactions\n" +
                "- **Circuit Breaker**: For resilience and fault tolerance\n" +
                "```\n\n" +

                "CODING STANDARDS:\n" +
                "- **Code Style**: Google Java Style Guide\n" +
                "- **Testing**: Minimum 80% code coverage\n" +
                "- **Documentation**: Javadoc for all public APIs\n" +
                "- **Security**: OWASP guidelines compliance\n" +
                "- **Performance**: Response time SLAs documented\n";
    }

    private String generateGeneralDocumentationGuidance() {
        return "General Documentation Best Practices:\n\n" +
                "DOCUMENTATION PRINCIPLES:\n" +
                "1. **User-Centric**: Write for your audience's needs and skill level\n" +
                "2. **Actionable**: Provide clear, step-by-step instructions\n" +
                "3. **Maintainable**: Keep documentation close to code and automated\n" +
                "4. **Discoverable**: Use consistent structure and navigation\n" +
                "5. **Accurate**: Regular reviews and updates with code changes\n\n" +

                "DOCUMENTATION HIERARCHY:\n" +
                "- **README**: Quick start and overview\n" +
                "- **API Docs**: Comprehensive endpoint documentation\n" +
                "- **Architecture**: System design and decisions\n" +
                "- **User Guides**: End-user procedures and workflows\n" +
                "- **Developer Guides**: Technical implementation details\n" +
                "- **Troubleshooting**: Common issues and solutions\n\n" +

                "QUALITY CHECKLIST:\n" +
                "✓ Clear purpose and scope defined\n" +
                "✓ Target audience identified\n" +
                "✓ Consistent formatting and style\n" +
                "✓ Code examples tested and working\n" +
                "✓ Links verified and functional\n" +
                "✓ Regular review schedule established\n" +
                "✓ Feedback mechanism provided\n\n" +

                "MICROSERVICES DOCUMENTATION STRATEGY:\n" +
                "- **Service Catalog**: Central registry of all services\n" +
                "- **API Contracts**: OpenAPI specifications for all endpoints\n" +
                "- **Integration Guides**: Service-to-service communication patterns\n" +
                "- **Deployment Docs**: Container and orchestration instructions\n" +
                "- **Monitoring Guides**: Observability and troubleshooting procedures\n";
    }
}