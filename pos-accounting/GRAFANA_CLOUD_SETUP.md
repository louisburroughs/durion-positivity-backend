# Grafana Cloud Setup for pos-accounting

## How to Set OTEL_EXPORTER_OTLP_HEADERS at Runtime

The Grafana Cloud API key **must not be hardcoded** in the Dockerfile. Set it at runtime using one of these methods:

### Option 1: Docker Run (Direct)

```bash
docker run -d \
  -e OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic <BASE64_ENCODED_CREDENTIALS>" \
  -p 9001:9001 \
  pos-accounting:latest
```

**How to get BASE64_ENCODED_CREDENTIALS:**
```bash
# Format: instance_id:api_key
echo -n "YOUR_INSTANCE_ID:YOUR_GRAFANA_API_KEY" | base64
```

### Option 2: Docker Compose

```yaml
services:
  pos-accounting:
    image: pos-accounting:latest
    environment:
      OTEL_EXPORTER_OTLP_HEADERS: "Authorization=Basic ${GRAFANA_CLOUD_TOKEN}"
    ports:
      - "9001:9001"
```

Then create a `.env` file (add to `.gitignore`):
```bash
# .env
GRAFANA_CLOUD_TOKEN=<BASE64_ENCODED_CREDENTIALS>
```

### Option 3: Kubernetes Secret

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: grafana-cloud-secret
type: Opaque
stringData:
  otel-headers: "Authorization=Basic <BASE64_ENCODED_CREDENTIALS>"
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: pos-accounting
spec:
  template:
    spec:
      containers:
      - name: pos-accounting
        image: pos-accounting:latest
        env:
        - name: OTEL_EXPORTER_OTLP_HEADERS
          valueFrom:
            secretKeyRef:
              name: grafana-cloud-secret
              key: otel-headers
```

### Option 4: Environment Variable File

```bash
# grafana-env.sh (add to .gitignore)
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic <BASE64_ENCODED_CREDENTIALS>"
```

Then source before running:
```bash
source grafana-env.sh
docker run -d \
  -e OTEL_EXPORTER_OTLP_HEADERS \
  -p 9001:9001 \
  pos-accounting:latest
```

## Getting Your Grafana Cloud Credentials

1. Go to https://louisburroughs.grafana.net/
2. Navigate to **Connections → Add new connection → OpenTelemetry (OTLP)**
3. Or go to **Connections → Data sources → Grafana Cloud OTLP**
4. Copy your:
   - **Instance ID** (e.g., `123456`)
   - **API Token** (create one with "MetricsPublisher" role)

5. Encode them:
   ```bash
   echo -n "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" | base64
   ```

## Verification

Once running, verify telemetry is being sent:

```bash
# Check application logs for OTLP export
docker logs pos-accounting | grep -i "otlp\|telemetry"

# Check Grafana Cloud
# Go to https://louisburroughs.grafana.net/ → Explore → Search for service.name="pos-accounting"
```

## Dockerfile Changes Applied

✅ Downloads Grafana OpenTelemetry Java agent (v2.9.0)  
✅ Adds `-javaagent:/opt/grafana-opentelemetry-java.jar` to ENTRYPOINT  
✅ Sets default OTLP endpoint for Grafana Cloud  
✅ Configures service name and resource attributes  
✅ **Does not** hardcode secrets in the image  

## Additional Configuration (Optional)

Override at runtime if needed:

```bash
docker run -d \
  -e OTEL_EXPORTER_OTLP_ENDPOINT="https://otlp-gateway-prod-us-east-3.grafana.net/otlp" \
  -e OTEL_SERVICE_NAME="pos-accounting" \
  -e OTEL_RESOURCE_ATTRIBUTES="service.version=1.0.0,deployment.environment=production" \
  -e OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic <BASE64>" \
  -p 9001:9001 \
  pos-accounting:latest
```

## Security Best Practices

🔒 **Never commit** Grafana API keys to version control  
🔒 **Use secrets management** (Kubernetes Secrets, AWS Secrets Manager, HashiCorp Vault)  
🔒 **Rotate tokens** regularly (every 90 days)  
🔒 **Use minimal permissions** (MetricsPublisher role only)  
🔒 **Add to `.gitignore`**: `.env`, `*-env.sh`, `grafana-*.txt`
