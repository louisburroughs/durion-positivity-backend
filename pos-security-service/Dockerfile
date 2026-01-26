FROM openjdk:21-jdk-alpine
VOLUME /tmp
ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS
COPY target/pos-security-service-*.jar pos-security-service.jar
EXPOSE 8086
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar pos-security-service.jar"]