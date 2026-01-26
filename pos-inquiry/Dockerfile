FROM openjdk:21-jdk-alpine
VOLUME /tmp
ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS
COPY target/pos-inquiry-*.jar pos-inquiry.jar
EXPOSE 9004
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar pos-inquiry.jar"]