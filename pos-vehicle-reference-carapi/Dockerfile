FROM openjdk:21-jdk-alpine
VOLUME /tmp
ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS
COPY target/pos-vehicle-reference-carapi-*.jar pos-vehicle-reference-carapi.jar
EXPOSE 8091
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar pos-vehicle-reference-carapi.jar"]