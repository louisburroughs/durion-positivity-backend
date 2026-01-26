FROM openjdk:21-jdk-alpine
VOLUME /tmp
ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS
COPY target/pos-vehicle-inventory-*.jar pos-vehicle-inventory.jar
EXPOSE 8089
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar pos-vehicle-inventory.jar"]