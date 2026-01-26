FROM openjdk:21-jdk-alpine
VOLUME /tmp
ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS
COPY target/pos-events-*.jar pos-events.jar
EXPOSE 9003
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar pos-events.jar"]