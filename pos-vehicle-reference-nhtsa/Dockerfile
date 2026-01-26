FROM openjdk:21-jdk-alpine
VOLUME /tmp
ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS
COPY target/pos-vehicle-reference-nhtsa-*.jar pos-vehicle-reference-nhtsa.jar
EXPOSE 8092
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar pos-vehicle-reference-nhtsa.jar"]