FROM eclipse-temurin:25-jdk-alpine

# libheif-tools: heif-dec, the HEIC decoder the parse sandbox runs as a grandchild process (S26 ruling 6).
# fontconfig and ttf-dejavu: fonts for PDF pages that do not embed theirs.
RUN apk add --no-cache libheif-tools fontconfig ttf-dejavu \
    && addgroup -g 10001 -S worker \
    && adduser -u 10001 -S -G worker worker

# Download Grafana OpenTelemetry Java Agent (world-readable: the container runs as a non-root user)
ARG GRAFANA_OTEL_AGENT_VERSION=v2.9.0
ADD --chmod=0644 https://github.com/grafana/grafana-opentelemetry-java/releases/download/${GRAFANA_OTEL_AGENT_VERSION}/grafana-opentelemetry-java.jar /opt/grafana-opentelemetry-java.jar

# The web JVM never parses a file, so it stays small; each parse is a child JVM with its own -Xmx.
ARG JAVA_OPTS="-Xmx256m -XX:-UsePerfData"
ENV JAVA_OPTS=$JAVA_OPTS
ENV OTEL_RESOURCE_ATTRIBUTES="service.name=DURION-POSITIVITY-BACKEND,service.namespace=durion-group,deployment.environment=pre-production"
ENV OTEL_SERVICE_NAME=pos-extraction-worker
ARG OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
ENV OTEL_EXPORTER_OTLP_ENDPOINT=$OTEL_EXPORTER_OTLP_ENDPOINT
ENV OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
# The scratch directory is a tmpfs in Compose; the image carries an empty one for plain `docker run`.
ENV POS_EXTRACTION_WORKER_SCRATCH_DIR=/scratch
RUN mkdir /scratch && chown 10001:10001 /scratch && chmod 0700 /scratch

# Unpack the jar so the child JVM starts from a plain classpath (app jar + lib/*) instead of the Spring Boot
# loader: java.class.path of this JVM is then the child's classpath as well.
COPY target/pos-extraction-worker.jar /tmp/pos-extraction-worker.jar
RUN java -Djarmode=tools -jar /tmp/pos-extraction-worker.jar extract --destination /app \
    && rm /tmp/pos-extraction-worker.jar

USER 10001
WORKDIR /app
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java -javaagent:/opt/grafana-opentelemetry-java.jar $JAVA_OPTS -jar /app/pos-extraction-worker.jar"]
