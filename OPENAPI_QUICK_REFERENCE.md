# OpenAPI Integration - Quick Reference Guide

## 🚀 Quick Commands

### Generate All Specs
```bash
cd /home/louisb/Projects/durion-positivity-backend

for module in pos-inventory pos-location pos-order pos-people pos-price pos-security-service; do
  echo "Building $module..."
  ./mvnw -Popenapi verify -pl $module -am -DskipTests
  echo "✅ $module spec generated at $module/target/openapi.json"
done
```

### Run Individual Module
```bash
# pos-security-service example
./mvnw -pl pos-security-service spring-boot:run

# Then access at http://localhost:8080/swagger-ui.html
```

### View Spec File
```bash
# View JSON
cat pos-security-service/target/openapi.json | jq .

# Or pretty print
jq '.' pos-security-service/target/openapi.json
```

## 📍 Key Files Per Module

### pos-security-service
- **Config**: `pos-security-service/pom.xml`
- **Security**: `pos-security-service/config/SecurityConfig.java`
- **Application**: `pos-security-service/PosSecurityServiceApplication.java`
- **Spec**: `pos-security-service/target/openapi.json`
- **Status**: `pos-security-service/OPENAPI_GENERATION_STATUS.md`
- **Log**: `pos-security-service/Durion-Processing.md`

## 🔗 OpenAPI Endpoints (When Running)

```
Swagger UI:        http://localhost:8080/swagger-ui.html
OpenAPI JSON:      http://localhost:8080/v3/api-docs
OpenAPI YAML:      http://localhost:8080/v3/api-docs.yaml
Swagger UI Assets: http://localhost:8080/swagger-ui/
H2 Console:        http://localhost:8080/h2-console
Health Check:      http://localhost:8080/actuator/health
```

## 📊 Module Overview

| Module | Spec Size | Endpoints | Controllers |
|--------|-----------|-----------|-------------|
| pos-inventory | 19 KB | ~15 | 6 |
| pos-location | 11 KB | ~8 | 3 |
| pos-order | 8.1 KB | 6 | 1 |
| pos-people | 15 KB | 21 | 7 |
| pos-price | 2.3 KB | 3 | 2 |
| pos-security-service | 16 KB | 12+ | 4 |

## 🔧 Standard Pattern

All modules follow this structure:

```
pom.xml
├── springdoc 2.7.0 dependency
├── spring-boot-maven-plugin
├── springdoc-openapi-maven-plugin
└── openapi Maven profile

SecurityConfig.java
├── @EnableWebSecurity
├── @EnableMethodSecurity
└── Permits: /v3/api-docs/**, /swagger-ui/**, /actuator/health

Application.java
└── @OpenAPIDefinition(title, version, description)

Controllers
├── @Tag (group)
├── @Operation (endpoint)
├── @ApiResponse (status codes)
└── @Parameter (parameters)
```

## 🏗️ Build Variants

### Full Build (with tests)
```bash
./mvnw -pl pos-security-service -am clean package
```

### Fast Build (skip tests)
```bash
./mvnw -pl pos-security-service -am clean package -DskipTests
```

### Generate Spec Only
```bash
./mvnw -Popenapi verify -pl pos-security-service -am -DskipTests
```

### Run Application
```bash
./mvnw -pl pos-security-service spring-boot:run
```

## 📖 Documentation Files

### Workspace Level
- `OPENAPI_INTEGRATION_STATUS.md` - Master status overview
- `OPENAPI_VERIFICATION_CHECKLIST.md` - Complete verification checklist

### Backend Root
- `OPENAPI_COMPLETION_SUMMARY.md` - Technical implementation details

### Per Module
- `OPENAPI_GENERATION_STATUS.md` - Status and API endpoints
- `Durion-Processing.md` - Detailed processing log

## 🔐 Security Access

When application is running:

### Public Endpoints (No Auth)
- `/v3/api-docs/**` - OpenAPI specification
- `/swagger-ui/**` - Swagger UI assets
- `/swagger-ui.html` - Swagger UI main page
- `/actuator/health` - Health check
- `/api/jwt/generate` - JWT generation
- `/api/users/login` - User login
- `/api/users` - User registration (POST)

### Protected Endpoints
- All other endpoints require JWT authentication
- Add header: `Authorization: Bearer <token>`

## 🐛 Troubleshooting

### Port Already in Use
```bash
# Kill process on port 8080
lsof -i :8080
kill -9 <PID>

# Or use different port
./mvnw -Dserver.port=9000 -pl pos-security-service spring-boot:run
```

### SSL/Keystore Error
```
Error: FileNotFoundException: classpath:keystore.p12

Solution: Already fixed in openapi profile
- --server.ssl.enabled=false is set in pom.xml
- Automatically used when generating specs
```

### Class Not Found: jakarta.servlet.Filter
```
Error: ClassNotFoundException: jakarta.servlet.Filter

Solution: Already fixed for all modules
- spring-boot-starter-web dependency is present
- Required for Spring Security
```

### Spring Security 401 Blocking
```
Error: 401 during spec generation

Solution: Already fixed in all modules
- SecurityConfig.java permits OpenAPI endpoints
- /v3/api-docs/** and /swagger-ui/** are allowed
```

## 🎯 Next Steps

### View Specs Immediately
```bash
# Start module
./mvnw -pl pos-security-service spring-boot:run &

# Wait for startup (check for "Started" message)
sleep 5

# View Swagger UI
open http://localhost:8080/swagger-ui.html
```

### Generate All Specs at Once
```bash
cd /home/louisb/Projects/durion-positivity-backend

# Generate all 6 specs
for module in pos-inventory pos-location pos-order pos-people pos-price pos-security-service; do
  ./mvnw -Popenapi verify -pl $module -am -DskipTests
done

# Verify all files created
for module in pos-inventory pos-location pos-order pos-people pos-price pos-security-service; do
  ls -lh $module/target/openapi.json
done
```

### Import Into Tools

#### Swagger Editor
```bash
# Copy spec file
cp pos-security-service/target/openapi.json ./myspec.json

# Open at https://editor.swagger.io/
# Then use "File" → "Import File" → select myspec.json
```

#### Postman
```
1. Click "Import" in Postman
2. Select "File"
3. Choose openapi.json
4. Collections created automatically with all endpoints
```

#### Code Generation
```bash
# Generate client SDK from spec
openapi-generator-cli generate \
  -i pos-security-service/target/openapi.json \
  -g java \
  -o ./generated-client

# Or use online tool: https://openapi-generator.tech/
```

## 📞 Support

### Documentation Files
- **Detailed Status**: `OPENAPI_GENERATION_STATUS.md` (per module)
- **Processing Log**: `Durion-Processing.md` (per module)
- **Technical Details**: `OPENAPI_COMPLETION_SUMMARY.md` (backend root)
- **Integration Status**: `OPENAPI_INTEGRATION_STATUS.md` (workspace root)

### Key Files Modified
- Each module's `pom.xml` - Maven configuration
- `SecurityConfig.java` (created/updated per module) - Security configuration
- Application class (verified/updated) - OpenAPI definition

## ✅ Verification Checklist

Run this to verify everything is ready:

```bash
cd /home/louisb/Projects/durion-positivity-backend

echo "✅ Checking OpenAPI specs..."
for module in pos-inventory pos-location pos-order pos-people pos-price pos-security-service; do
  if [ -f "$module/target/openapi.json" ]; then
    size=$(ls -lh "$module/target/openapi.json" | awk '{print $5}')
    echo "  ✅ $module: $size"
  else
    echo "  ❌ $module: MISSING"
  fi
done

echo ""
echo "✅ Checking documentation..."
for module in pos-inventory pos-location pos-order pos-people pos-price pos-security-service; do
  if [ -f "$module/OPENAPI_GENERATION_STATUS.md" ]; then
    echo "  ✅ $module: Status file exists"
  fi
done
```

---

**Version**: 1.0  
**Updated**: 2025-01-27  
**Status**: ✅ Complete & Ready
