# Сборка и запуск сервиса heatnet. Java 11, Spring Boot 2.6.3, Maven (ТЗ раздел 3.2).
FROM maven:3.8.6-eclipse-temurin-11 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:11-jre
RUN mkdir -p /var/lib/heatnet /tmp/heatnet
WORKDIR /app
COPY --from=build /build/target/heatnet.jar /app/heatnet.jar
# образцы для просмотрщика раздаёт сам сервис (/api/samples), поэтому они кладутся в образ
COPY data/samples /app/data/samples
EXPOSE 8080
ENV JAVA_OPTS="-Xmx12g -XX:+UseG1GC -XX:MaxGCPauseMillis=500"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/heatnet.jar"]
