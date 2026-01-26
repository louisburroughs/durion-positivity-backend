FROM openjdk:21-jdk-alpine
VOLUME /tmp
ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS
COPY target/pos-inventory-*.jar pos-inventory.jar
EXPOSE 9005
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar pos-inventory.jar"]