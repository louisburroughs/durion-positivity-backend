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
 * Security Agent - Provides comprehensive security across microservices and
 * data stores
 * 
 * Implements REQ-007 capabilities:
 * - JWT, Spring Security, and token-based authentication implementation
 * - Authorization, input validation, and OWASP compliance
 * - AWS Secrets Manager, IAM roles, and secure configuration
 * - Encryption at rest and in transit implementation
 * - API Gateway security, rate limiting, and threat protection
 */
@Component
public class SecurityAgent extends BaseAgent {

    public SecurityAgent() {
        super(
                "security-agent",
                "Security Agent",
                "security",
                Set.of("jwt", "spring-security", "authentication", "authorization", "owasp",
                        "input-validation", "secrets-manager", "iam", "encryption", "tls", "ssl",
                        "api-security", "rate-limiting", "threat-protection", "oauth2", "cors",
                        "csrf", "xss", "sql-injection", "security-headers", "password-hashing"),
                Set.of(), // No dependencies
                new AgentPerformanceSpec(Duration.ofSeconds(3), 0.94, 0.999, 10, Duration.ofMinutes(5)));
    }

    @Override
    protected AgentGuidanceResponse processGuidanceRequest(AgentConsultationRequest request) {
        return AgentGuidanceResponse.success(
                request.requestId(),
                getId(),
                generateSecurityGuidance(request),
                0.94,
                List.of("Security Implementation", "Authentication Patterns", "OWASP Compliance"),
                Duration.ZERO);
    }

    private String generateSecurityGuidance(AgentConsultationRequest request) {
        String baseGuidance = "POS System Security Analysis for " + request.domain() + ":\n\n";
        String query = request.query().toLowerCase();

        // JWT and token-based authentication (REQ-007.1)
        if (query.contains("jwt") || query.contains("token") || query.contains("authentication") ||
                query.contains("login") || query.contains("auth")) {
            return baseGuidance + generateJwtAuthenticationGuidance(request);
        }

        // OWASP vulnerabilities (XSS, CSRF, SQL Injection) - prioritize specific
        // vulnerabilities
        if (query.contains("xss") || query.contains("csrf") || query.contains("sql-injection") ||
                query.contains("vulnerability") || query.contains("security-headers") ||
                query.contains("cross-site")) {
            return baseGuidance + generateOwaspVulnerabilityGuidance(request);
        }

        // Authorization and OWASP compliance (REQ-007.2)
        if (query.contains("authorization") || query.contains("owasp") || query.contains("compliance") ||
                query.contains("input-validation") || query.contains("validation")) {
            return baseGuidance + generateAuthorizationComplianceGuidance(request);
        }

        // AWS Secrets Manager and IAM roles (REQ-007.3)
        if (query.contains("secrets") || query.contains("iam") || query.contains("aws") ||
                query.contains("configuration") || query.contains("credentials")) {
            return baseGuidance + generateSecretsManagementGuidance(request);
        }

        // Encryption at rest and in transit (REQ-007.4)
        if (query.contains("encryption") || query.contains("tls") || query.contains("ssl") ||
                query.contains("transit") || query.contains("rest") || query.contains("crypto")) {
            return baseGuidance + generateEncryptionGuidance(request);
        }

        // API Gateway security and threat protection (REQ-007.5)
        if (query.contains("api-security") || query.contains("gateway-security") || query.contains("waf") ||
                query.contains("rate-limiting") || query.contains("threat") || query.contains("ddos")) {
            return baseGuidance + generateApiGatewaySecurityGuidance(request);
        }

        // Spring Security configuration
        if (query.contains("spring-security") || query.contains("security-config") ||
                query.contains("web-security")) {
            return baseGuidance + generateSpringSecurityGuidance(request);
        }

        // General security guidance
        return baseGuidance + generateGeneralSecurityGuidance();
    }

    private String generateJwtAuthenticationGuidance(AgentConsultationRequest request) {
        return "JWT & Token-Based Authentication Implementation:\n\n" +
                "JWT TOKEN STRUCTURE:\n" +
                "- Header: Algorithm (RS256/HS256), Token Type (JWT)\n" +
                "- Payload: Claims (sub, iss, exp, iat, roles, permissions)\n" +
                "- Signature: HMAC SHA256 or RSA SHA256 for verification\n\n" +

                "SPRING SECURITY JWT CONFIGURATION:\n" +
                "```java\n" +
                "@EnableWebSecurity\n" +
                "public class SecurityConfig {\n" +
                "    @Bean\n" +
                "    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {\n" +
                "        return http\n" +
                "            .csrf(csrf -> csrf.disable())\n" +
                "            .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))\n" +
                "            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))\n" +
                "            .authorizeHttpRequests(auth -> auth\n" +
                "                .requestMatchers(\"/api/v1/auth/**\").permitAll()\n" +
                "                .requestMatchers(\"/api/v1/admin/**\").hasRole(\"ADMIN\")\n" +
                "                .anyRequest().authenticated())\n" +
                "            .build();\n" +
                "    }\n" +
                "}\n" +
                "```\n\n" +

                "TOKEN LIFECYCLE MANAGEMENT:\n" +
                "- Access Token: Short-lived (15-30 minutes), contains user claims\n" +
                "- Refresh Token: Long-lived (7-30 days), stored securely, rotated on use\n" +
                "- Token Revocation: Maintain blacklist or use short expiration times\n" +
                "- Token Validation: Verify signature, expiration, issuer, and audience\n\n" +

                "POS-SPECIFIC AUTHENTICATION PATTERNS:\n" +
                "- Employee Authentication: Role-based access (CASHIER, MANAGER, ADMIN)\n" +
                "- Customer Authentication: Optional for loyalty programs and order history\n" +
                "- Service-to-Service: Use client credentials flow with service accounts\n" +
                "- Multi-tenant: Include tenant ID in JWT claims for data isolation\n";
    }

    private String generateAuthorizationComplianceGuidance(AgentConsultationRequest request) {
        return "Authorization & OWASP Compliance Implementation:\n\n" +
                "ROLE-BASED ACCESS CONTROL (RBAC):\n" +
                "- Define Roles: ADMIN, MANAGER, CASHIER, CUSTOMER, SERVICE_ACCOUNT\n" +
                "- Permission Mapping: CREATE_ORDER, VIEW_REPORTS, MANAGE_INVENTORY, PROCESS_PAYMENT\n" +
                "- Method-Level Security: @PreAuthorize(\"hasRole('MANAGER') or hasPermission(#orderId, 'ORDER', 'READ')\")\n\n"
                +

                "INPUT VALIDATION & SANITIZATION:\n" +
                "```java\n" +
                "@Valid\n" +
                "public ResponseEntity<Order> createOrder(@RequestBody @Valid OrderRequest request) {\n" +
                "    // Bean Validation with @NotNull, @Size, @Pattern, @Email\n" +
                "    // Custom validators for business rules\n" +
                "    // SQL injection prevention with parameterized queries\n" +
                "}\n" +
                "```\n\n" +

                "OWASP TOP 10 COMPLIANCE:\n" +
                "1. Injection Prevention: Use parameterized queries, input validation\n" +
                "2. Broken Authentication: Implement secure session management, MFA\n" +
                "3. Sensitive Data Exposure: Encrypt PII, use HTTPS, secure headers\n" +
                "4. XML External Entities: Disable XXE in XML parsers\n" +
                "5. Broken Access Control: Implement proper authorization checks\n" +
                "6. Security Misconfiguration: Secure defaults, regular updates\n" +
                "7. Cross-Site Scripting: Output encoding, CSP headers\n" +
                "8. Insecure Deserialization: Validate serialized objects\n" +
                "9. Known Vulnerabilities: Dependency scanning, regular updates\n" +
                "10. Insufficient Logging: Comprehensive audit trails\n\n" +

                "POS AUTHORIZATION PATTERNS:\n" +
                "- Order Access: Customers can only view their own orders\n" +
                "- Inventory Management: Requires MANAGER role or higher\n" +
                "- Payment Processing: Requires CASHIER role with PCI compliance\n" +
                "- Customer Data: GDPR compliance with data access controls\n";
    }

    private String generateSecretsManagementGuidance(AgentConsultationRequest request) {
        return "AWS Secrets Manager & IAM Roles Configuration:\n\n" +
                "SECRETS MANAGER INTEGRATION:\n" +
                "```java\n" +
                "@Configuration\n" +
                "public class SecretsConfig {\n" +
                "    @Bean\n" +
                "    public SecretsManagerClient secretsClient() {\n" +
                "        return SecretsManagerClient.builder()\n" +
                "            .region(Region.US_EAST_1)\n" +
                "            .credentialsProvider(DefaultCredentialsProvider.create())\n" +
                "            .build();\n" +
                "    }\n" +
                "    \n" +
                "    @Value(\"#{@secretsService.getSecret('pos/database/credentials')}\")\n" +
                "    private String databasePassword;\n" +
                "}\n" +
                "```\n\n" +

                "IAM ROLES & POLICIES:\n" +
                "- Service Roles: pos-catalog-service-role, pos-order-service-role\n" +
                "- Least Privilege: Grant minimum permissions required\n" +
                "- Cross-Account Access: Use assume role for multi-account deployments\n" +
                "- Resource-Based Policies: Restrict access to specific resources\n\n" +

                "SECRET ROTATION STRATEGY:\n" +
                "- Database Credentials: Automatic rotation every 30 days\n" +
                "- API Keys: Manual rotation every 90 days with notification\n" +
                "- JWT Signing Keys: Rotation every 6 months with key versioning\n" +
                "- Encryption Keys: Annual rotation with backward compatibility\n\n" +

                "SECURE CONFIGURATION PATTERNS:\n" +
                "- Environment Variables: Use for non-sensitive configuration\n" +
                "- Secrets Manager: Store database passwords, API keys, certificates\n" +
                "- Parameter Store: Store application configuration with encryption\n" +
                "- Kubernetes Secrets: For container-based deployments\n\n" +

                "POS SECRETS MANAGEMENT:\n" +
                "- Payment Gateway Keys: PCI-compliant storage and rotation\n" +
                "- Database Connections: Per-service credentials with rotation\n" +
                "- Third-party APIs: Vehicle reference APIs, tax calculation services\n" +
                "- Encryption Keys: Customer PII, payment data, audit logs\n";
    }

    private String generateEncryptionGuidance(AgentConsultationRequest request) {
        return "Encryption at Rest and in Transit Implementation:\n\n" +
                "ENCRYPTION IN TRANSIT (TLS/SSL):\n" +
                "- TLS 1.3: Use latest protocol version for all communications\n" +
                "- Certificate Management: Use Let's Encrypt or AWS Certificate Manager\n" +
                "- HSTS Headers: Enforce HTTPS with Strict-Transport-Security\n" +
                "- Perfect Forward Secrecy: Use ECDHE key exchange\n\n" +

                "ENCRYPTION AT REST:\n" +
                "```java\n" +
                "@Entity\n" +
                "public class Customer {\n" +
                "    @Convert(converter = EncryptedStringConverter.class)\n" +
                "    private String socialSecurityNumber;\n" +
                "    \n" +
                "    @Convert(converter = EncryptedStringConverter.class)\n" +
                "    private String creditCardNumber;\n" +
                "}\n" +
                "```\n\n" +

                "DATABASE ENCRYPTION:\n" +
                "- PostgreSQL: Enable transparent data encryption (TDE)\n" +
                "- Column-Level: Encrypt PII fields (SSN, credit cards, addresses)\n" +
                "- Key Management: Use AWS KMS for encryption key management\n" +
                "- Backup Encryption: Ensure encrypted backups and point-in-time recovery\n\n" +

                "APPLICATION-LEVEL ENCRYPTION:\n" +
                "- AES-256-GCM: For symmetric encryption of sensitive data\n" +
                "- RSA-4096: For asymmetric encryption and key exchange\n" +
                "- Argon2id: For password hashing with salt and pepper\n" +
                "- HMAC-SHA256: For message authentication and integrity\n\n" +

                "POS ENCRYPTION REQUIREMENTS:\n" +
                "- Customer PII: Full encryption with field-level granularity\n" +
                "- Payment Data: PCI DSS compliance with tokenization\n" +
                "- Audit Logs: Tamper-proof encryption with digital signatures\n" +
                "- Inter-Service Communication: mTLS for service-to-service calls\n";
    }

    private String generateApiGatewaySecurityGuidance(AgentConsultationRequest request) {
        return "API Gateway Security & Threat Protection:\n\n" +
                "WEB APPLICATION FIREWALL (WAF):\n" +
                "- SQL Injection Protection: Block malicious SQL patterns\n" +
                "- XSS Prevention: Filter script injection attempts\n" +
                "- Rate Limiting: Implement per-IP and per-user limits\n" +
                "- Geo-blocking: Restrict access from high-risk countries\n\n" +

                "RATE LIMITING STRATEGIES:\n" +
                "```yaml\n" +
                "rate-limiting:\n" +
                "  global: 1000 requests/minute\n" +
                "  per-user: 100 requests/minute\n" +
                "  per-ip: 200 requests/minute\n" +
                "  burst-capacity: 50\n" +
                "  algorithms:\n" +
                "    - token-bucket\n" +
                "    - sliding-window\n" +
                "```\n\n" +

                "DDOS PROTECTION:\n" +
                "- AWS Shield: Basic DDoS protection for all resources\n" +
                "- CloudFlare: Advanced DDoS mitigation with global CDN\n" +
                "- Circuit Breakers: Prevent cascade failures during attacks\n" +
                "- Health Checks: Automatic failover to healthy instances\n\n" +

                "API SECURITY HEADERS:\n" +
                "```http\n" +
                "Strict-Transport-Security: max-age=31536000; includeSubDomains\n" +
                "Content-Security-Policy: default-src 'self'; script-src 'self'\n" +
                "X-Frame-Options: DENY\n" +
                "X-Content-Type-Options: nosniff\n" +
                "X-XSS-Protection: 1; mode=block\n" +
                "Referrer-Policy: strict-origin-when-cross-origin\n" +
                "```\n\n" +

                "POS API SECURITY PATTERNS:\n" +
                "- Customer APIs: Rate limiting based on customer tier\n" +
                "- Payment APIs: Enhanced security with fraud detection\n" +
                "- Admin APIs: IP whitelisting and multi-factor authentication\n" +
                "- Public APIs: CORS configuration and API key validation\n";
    }

    private String generateSpringSecurityGuidance(AgentConsultationRequest request) {
        return "Spring Security Configuration & Best Practices:\n\n" +
                "SECURITY FILTER CHAIN:\n" +
                "```java\n" +
                "@Configuration\n" +
                "@EnableWebSecurity\n" +
                "@EnableMethodSecurity(prePostEnabled = true)\n" +
                "public class WebSecurityConfig {\n" +
                "    \n" +
                "    @Bean\n" +
                "    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {\n" +
                "        return http\n" +
                "            .csrf(csrf -> csrf\n" +
                "                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())\n" +
                "                .ignoringRequestMatchers(\"/api/v1/webhooks/**\"))\n" +
                "            .sessionManagement(session -> session\n" +
                "                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))\n" +
                "            .oauth2ResourceServer(oauth2 -> oauth2\n" +
                "                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter())))\n" +
                "            .authorizeHttpRequests(auth -> auth\n" +
                "                .requestMatchers(\"/actuator/health\").permitAll()\n" +
                "                .requestMatchers(\"/api/v1/public/**\").permitAll()\n" +
                "                .requestMatchers(HttpMethod.POST, \"/api/v1/orders\").hasRole(\"CASHIER\")\n" +
                "                .requestMatchers(\"/api/v1/admin/**\").hasRole(\"ADMIN\")\n" +
                "                .anyRequest().authenticated())\n" +
                "            .build();\n" +
                "    }\n" +
                "}\n" +
                "```\n\n" +

                "METHOD-LEVEL SECURITY:\n" +
                "```java\n" +
                "@PreAuthorize(\"hasRole('MANAGER') or @orderService.isOwner(#orderId, authentication.name)\")\n" +
                "public Order getOrder(Long orderId) { ... }\n" +
                "\n" +
                "@PostAuthorize(\"returnObject.customerId == authentication.principal.customerId\")\n" +
                "public Order createOrder(OrderRequest request) { ... }\n" +
                "```\n\n" +

                "CUSTOM AUTHENTICATION PROVIDERS:\n" +
                "- Database Authentication: Custom UserDetailsService implementation\n" +
                "- LDAP Integration: For enterprise employee authentication\n" +
                "- Multi-Factor Authentication: TOTP or SMS-based verification\n" +
                "- Social Login: OAuth2 integration with Google, Facebook, Apple\n";
    }

    private String generateOwaspVulnerabilityGuidance(AgentConsultationRequest request) {
        return "OWASP Vulnerability Prevention & Security Headers:\n\n" +
                "CROSS-SITE SCRIPTING (XSS) PREVENTION:\n" +
                "```java\n" +
                "// Output Encoding\n" +
                "@GetMapping(\"/customer/{id}\")\n" +
                "public String getCustomer(@PathVariable String id, Model model) {\n" +
                "    String safeName = HtmlUtils.htmlEscape(customer.getName());\n" +
                "    model.addAttribute(\"customerName\", safeName);\n" +
                "    return \"customer-view\";\n" +
                "}\n" +
                "\n" +
                "// Content Security Policy\n" +
                "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'\n" +
                "```\n\n" +

                "CSRF PROTECTION:\n" +
                "```java\n" +
                "// Spring Security CSRF Configuration\n" +
                ".csrf(csrf -> csrf\n" +
                "    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())\n" +
                "    .ignoringRequestMatchers(\"/api/v1/webhooks/**\"))\n" +
                "\n" +
                "// Frontend CSRF Token Handling\n" +
                "const csrfToken = document.querySelector('meta[name=\"_csrf\"]').content;\n" +
                "axios.defaults.headers.common['X-CSRF-TOKEN'] = csrfToken;\n" +
                "```\n\n" +

                "SQL INJECTION PREVENTION:\n" +
                "```java\n" +
                "// Use JPA Queries with Parameters\n" +
                "@Query(\"SELECT c FROM Customer c WHERE c.email = :email AND c.status = :status\")\n" +
                "Optional<Customer> findByEmailAndStatus(@Param(\"email\") String email, \n" +
                "                                       @Param(\"status\") CustomerStatus status);\n" +
                "\n" +
                "// Avoid String Concatenation\n" +
                "// BAD: \"SELECT * FROM customers WHERE id = \" + userId\n" +
                "// GOOD: Use parameterized queries or JPA criteria API\n" +
                "```\n\n" +

                "SECURITY HEADERS CONFIGURATION:\n" +
                "```java\n" +
                "@Component\n" +
                "public class SecurityHeadersFilter implements Filter {\n" +
                "    @Override\n" +
                "    public void doFilter(ServletRequest request, ServletResponse response, \n" +
                "                        FilterChain chain) throws IOException, ServletException {\n" +
                "        HttpServletResponse httpResponse = (HttpServletResponse) response;\n" +
                "        httpResponse.setHeader(\"X-Frame-Options\", \"DENY\");\n" +
                "        httpResponse.setHeader(\"X-Content-Type-Options\", \"nosniff\");\n" +
                "        httpResponse.setHeader(\"X-XSS-Protection\", \"1; mode=block\");\n" +
                "        httpResponse.setHeader(\"Strict-Transport-Security\", \n" +
                "                              \"max-age=31536000; includeSubDomains\");\n" +
                "        chain.doFilter(request, response);\n" +
                "    }\n" +
                "}\n" +
                "```\n";
    }

    private String generateGeneralSecurityGuidance() {
        return "General Security Best Practices:\n\n" +
                "SECURITY DEVELOPMENT LIFECYCLE:\n" +
                "- Threat Modeling: Identify security risks during design phase\n" +
                "- Secure Coding: Follow OWASP secure coding practices\n" +
                "- Security Testing: Automated security scans in CI/CD pipeline\n" +
                "- Penetration Testing: Regular third-party security assessments\n\n" +

                "MONITORING & INCIDENT RESPONSE:\n" +
                "- Security Logging: Comprehensive audit trails for all security events\n" +
                "- Anomaly Detection: Monitor for unusual access patterns\n" +
                "- Incident Response: Defined procedures for security breaches\n" +
                "- Compliance Reporting: Regular security compliance assessments\n\n" +

                "POS SECURITY CONSIDERATIONS:\n" +
                "- PCI DSS Compliance: For payment card data handling\n" +
                "- GDPR Compliance: For customer personal data protection\n" +
                "- SOX Compliance: For financial data integrity and reporting\n" +
                "- Industry Standards: Follow retail and automotive security best practices\n";
    }
}