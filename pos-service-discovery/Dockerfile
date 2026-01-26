FROM openjdk:21-jdk-alpine
VOLUME /tmp
ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS
COPY target/pos-service-discovery-*.jar pos-service-discovery.jar
EXPOSE 8761
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar pos-service-discovery.jar"]