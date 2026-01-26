FROM openjdk:21-jdk-alpine
VOLUME /tmp
ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS
COPY target/pos-order-*.jar pos-order.jar
EXPOSE 9007
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar pos-order.jar"]