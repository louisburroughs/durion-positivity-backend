FROM openjdk:21-jdk-alpine
VOLUME /tmp
ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS
COPY target/pos-vehicle-fitment-*.jar pos-vehicle-fitment.jar
EXPOSE 8088
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar pos-vehicle-fitment.jar"]