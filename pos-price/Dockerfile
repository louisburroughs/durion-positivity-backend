FROM openjdk:21-jdk-alpine
VOLUME /tmp
ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS
COPY target/pos-price-*.jar pos-price.jar
EXPOSE 9008
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar pos-price.jar"]