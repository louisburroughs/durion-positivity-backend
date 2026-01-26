FROM openjdk:21-jdk-alpine
VOLUME /tmp
ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS
COPY target/pos-accounting-*.jar pos-accounting.jar
EXPOSE 9001
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar pos-accounting.jar"]