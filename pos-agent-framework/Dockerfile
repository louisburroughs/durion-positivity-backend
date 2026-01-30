# Multi-stage build for optimized agent framework image
FROM maven:3.9.4-openjdk-21-slim AS builder

# Set working directory
WORKDIR /app

# Copy pom.xml and download dependencies (for better layer caching)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# Production image
FROM openjdk:21-jre-slim

# Install curl for health checks and agent download
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Download Grafana OpenTelemetry Java Agent
ARG GRAFANA_OTEL_AGENT_VERSION=v2.9.0
RUN curl -L -o /opt/grafana-opentelemetry-java.jar \
    https://github.com/grafana/grafana-opentelemetry-java/releases/download/${GRAFANA_OTEL_AGENT_VERSION}/grafana-opentelemetry-java.jar

# Create non-root user for security
RUN groupadd -r agentuser && useradd -r -g agentuser agentuser

# Set working directory
WORKDIR /app

# Copy built JAR from builder stage
COPY --from=builder /app/target/pos-agent-framework-*.jar app.jar

# Change ownership to non-root user
RUN chown -R agentuser:agentuser /app && chown agentuser:agentuser /opt/grafana-opentelemetry-java.jar
USER agentuser

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# OpenTelemetry configuration (override at runtime as needed)
ENV OTEL_RESOURCE_ATTRIBUTES="service.name=DURION-POSITIVITY-BACKEND,service.namespace=durion-group,deployment.environment=pre-production"
ENV OTEL_SERVICE_NAME=pos-agent-framework
ENV OTEL_EXPORTER_OTLP_ENDPOINT=${OTEL_EXPORTER_OTLP_ENDPOINT:-https://otlp-gateway-prod-us-east-3.grafana.net/otlp}
ENV OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
# IMPORTANT: Set OTEL_EXPORTER_OTLP_HEADERS at runtime with your Grafana Cloud API key
# Example: -e OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic <base64-encoded-instance-id:api-key>"

# JVM optimization for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+UseStringDeduplication"

# Run the application with Java agent
ENTRYPOINT ["sh", "-c", "java -javaagent:/opt/grafana-opentelemetry-java.jar $JAVA_OPTS -jar app.jar"]

# Labels for metadata
LABEL maintainer="Positivity POS Team"
LABEL version="1.0.0"
LABEL description="Positivity Agent Framework - Intelligent development assistance"
LABEL org.opencontainers.image.source="https://github.com/positivity/pos-system"