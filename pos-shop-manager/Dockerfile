FROM openjdk:21-jdk-alpine
VOLUME /tmp
ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS
COPY target/pos-shop-manager-*.jar pos-shop-manager.jar
EXPOSE 8087
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar pos-shop-manager.jar"]