# Secrets Management Guide

## Problem Identified

Your `application.yml` files currently contain exposed secrets:
- Database passwords (e.g., `pos_password`)
- Keystore passwords (e.g., `changeit`)
- API keys and credentials in application configuration files

These are committed to version control, which is a **security risk**.

---

## Solution: Environment Variables + Spring Boot Profiles

### Step 1: Move Secrets to Environment Variables

Update your `application.yml` files to use environment variable placeholders:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/pos_catalog_db
    username: ${SPRING_DATASOURCE_USERNAME:pos_user}
    password: ${SPRING_DATASOURCE_PASSWORD}  # Required, no default
    driver-class-name: org.postgresql.Driver
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    hibernate:
      ddl-auto: update

server:
  ssl:
    enabled: true
    key-store: classpath:keystore.p12
    key-store-password: ${SSL_KEYSTORE_PASSWORD}  # Required
    key-store-type: PKCS12
    key-alias: pos-security-service
```

### Step 2: Update .gitignore

Add local secret files:

```gitignore
# Secrets Management
.env
.env.local
*.p12
keystore.p12
application-secrets.yml
/src/main/resources/application-secrets.yml
```

### Step 3: Set Environment Variables

#### Option A: Docker Compose (Recommended for local development)

Already configured in your `docker-compose.yml`:

```yaml
environment:
  SPRING_DATASOURCE_USERNAME: positivity
  SPRING_DATASOURCE_PASSWORD: positivity
  SSL_KEYSTORE_PASSWORD: changeit
```

#### Option B: System Environment Variables

```bash
# Linux/Mac
export SPRING_DATASOURCE_USERNAME=pos_user
export SPRING_DATASOURCE_PASSWORD=your_secure_password
export SSL_KEYSTORE_PASSWORD=your_keystore_password

# Windows (Command Prompt)
set SPRING_DATASOURCE_USERNAME=pos_user
set SPRING_DATASOURCE_PASSWORD=your_secure_password
set SSL_KEYSTORE_PASSWORD=your_keystore_password
```

#### Option C: .env File (for local development only)

Create `.env` in project root (add to .gitignore):

```env
SPRING_DATASOURCE_USERNAME=pos_user
SPRING_DATASOURCE_PASSWORD=local_dev_password
SSL_KEYSTORE_PASSWORD=local_keystore_password
```

Use with: `source .env` (Linux/Mac)

#### Option D: Spring Boot Profiles

Create `application-local.yml` (add to .gitignore):

```yaml
# application-local.yml
spring:
  datasource:
    username: pos_user
    password: local_dev_password

server:
  ssl:
    key-store-password: local_keystore_password
```

Activate with: `java -jar app.jar --spring.profiles.active=local`

### Step 4: Update docker-compose.yml

Your docker-compose.yml already has most secrets via environment variables. Ensure they're not hardcoded in `.yml` files:

```yaml
pos-catalog:
  build:
    context: ./pos-catalog
  environment:
    SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD:-positivity}
    SSL_KEYSTORE_PASSWORD: ${KEYSTORE_PASSWORD:-changeit}
```

Then pass from shell:
```bash
export DB_PASSWORD=production_password
docker-compose up
```

---

## Best Practices

### ✅ DO:
- Use Spring property placeholders: `${VARIABLE_NAME}`
- Provide defaults for non-critical configs: `${VARIABLE_NAME:default_value}`
- Store secrets in environment variables or secrets manager
- Use different credentials for dev/test/prod
- Rotate secrets regularly
- Add `.env`, `*.p12`, keystore files to `.gitignore`
- Use AWS Secrets Manager, HashiCorp Vault, or Azure Key Vault in production

### ❌ DON'T:
- Hardcode passwords in `application.yml` or `.java` files
- Commit `.env`, `application-secrets.yml`, or keystore files
- Use same password across environments
- Share secrets in logs or error messages
- Use weak default passwords even locally

---

## Implementation Checklist

### Immediate Actions (Today):

- [ ] Update all `application.yml` files to use environment variables
- [ ] Update `.gitignore` to exclude secret files
- [ ] Remove hardcoded secrets from version control:
  ```bash
  git rm --cached pos-*/src/main/resources/application.yml
  git commit -m "Remove secrets from version control"
  ```

### Short Term (This Week):

- [ ] Create `.env.example` file showing required variables (with dummy values)
- [ ] Document environment variable setup in README.md
- [ ] Update CI/CD to inject secrets at deployment time
- [ ] Rotate exposed passwords

### Long Term (This Month):

- [ ] Implement HashiCorp Vault or Spring Cloud Config Server
- [ ] Integrate with AWS Secrets Manager or Azure Key Vault
- [ ] Add secret scanning to CI pipeline (e.g., Trivy, git-secrets)
- [ ] Audit all committed code for exposed credentials

---

## Quick Implementation Example

### Before (❌ Insecure):
```yaml
spring:
  datasource:
    username: pos_user
    password: pos_password  # EXPOSED!
```

### After (✅ Secure):
```yaml
spring:
  datasource:
    username: ${SPRING_DATASOURCE_USERNAME:pos_user}
    password: ${SPRING_DATASOURCE_PASSWORD}  # From environment
```

### Running:
```bash
export SPRING_DATASOURCE_PASSWORD=your_real_password
./mvnw spring-boot:run
```

---

## Production Recommendation

Use **Spring Cloud Config Server** with **HashiCorp Vault**:

1. Central configuration server stores encrypted secrets
2. Services fetch config at startup
3. Secrets never touch application code or files
4. Full audit trail of secret access

Example setup:
```yaml
spring:
  cloud:
    config:
      uri: https://config-server:8888
      username: ${CONFIG_SERVER_USER}
      password: ${CONFIG_SERVER_PASSWORD}
  config:
    import: vault://localhost:8200
```

---

## Security Scanning

Add to your CI pipeline:

```bash
# Scan for exposed secrets
git-secrets --scan
trivy fs .
gitleaks detect --verbose
```

---

## Questions?

Refer to:
- [Spring Boot Externalized Configuration](https://spring.io/guides/gs/externalized-configuration/)
- [12 Factor App - Configuration](https://12factor.net/config)
- [OWASP Secrets Management](https://owasp.org/www-community/attacks/Sensitive_Data_Exposure)
