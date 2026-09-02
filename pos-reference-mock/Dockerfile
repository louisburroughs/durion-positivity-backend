FROM eclipse-temurin:25-jdk-alpine
VOLUME /tmp

# No OpenTelemetry agent on purpose: this container simulates an EXTERNAL vendor
# (service-time-sourcing-plan §10) and must not blend into platform observability
# as if it were a mesh service.

COPY target/pos-reference-mock.jar pos-reference-mock.jar
EXPOSE 8095
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar pos-reference-mock.jar"]
