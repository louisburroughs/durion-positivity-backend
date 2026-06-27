FROM eclipse-temurin:25-jdk-alpine
VOLUME /tmp

# Download Grafana OpenTelemetry Java Agent
ARG GRAFANA_OTEL_AGENT_VERSION=v2.9.0
ADD https://github.com/grafana/grafana-opentelemetry-java/releases/download/${GRAFANA_OTEL_AGENT_VERSION}/grafana-opentelemetry-java.jar /opt/grafana-opentelemetry-java.jar

# OpenTelemetry configuration (override at runtime as needed)
ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS
ENV OTEL_RESOURCE_ATTRIBUTES="service.name=DURION-POSITIVITY-BACKEND,service.namespace=durion-group,deployment.environment=pre-production"
ENV OTEL_SERVICE_NAME=pos-workorder
ARG OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
ENV OTEL_EXPORTER_OTLP_ENDPOINT=$OTEL_EXPORTER_OTLP_ENDPOINT
ENV OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
# Optional: Set OTEL_EXPORTER_OTLP_HEADERS at runtime if your OTLP endpoint requires authentication
# Default endpoint (otel-collector:4318) does not require authentication

WORKDIR /app
COPY target/pos-workorder.jar pos-workorder.jar
EXPOSE 8090
ENTRYPOINT ["sh", "-c", "exec java -javaagent:/opt/grafana-opentelemetry-java.jar $JAVA_OPTS -jar pos-workorder.jar"]