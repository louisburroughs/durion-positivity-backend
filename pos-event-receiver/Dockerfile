FROM openjdk:21-jdk-alpine
VOLUME /tmp
ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS
COPY target/pos-event-receiver-*.jar pos-event-receiver.jar
EXPOSE 9002
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar pos-event-receiver.jar"]