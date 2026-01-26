FROM openjdk:21-jdk-alpine
VOLUME /tmp
ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS
COPY target/pos-invoice-*.jar pos-invoice.jar
EXPOSE 9006
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar pos-invoice.jar"]